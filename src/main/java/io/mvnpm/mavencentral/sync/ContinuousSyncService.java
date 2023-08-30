package io.mvnpm.mavencentral.sync;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import io.mvnpm.Constants;
import io.mvnpm.file.FileStore;
import io.mvnpm.maven.NameVersionType;
import io.mvnpm.maven.RepoNameVersionType;
import io.mvnpm.mavencentral.MavenFacade;
import io.mvnpm.mavencentral.RepoStatus;
import io.mvnpm.npm.NpmRegistryFacade;
import io.mvnpm.npm.model.Name;
import io.mvnpm.npm.model.NameParser;
import io.mvnpm.npm.model.Project;
import io.smallrye.common.annotation.Blocking;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * This runs Continuous (on some schedule) and check if any updates for libraries we have is available, 
 * and if so, kick of a sync. Can also be triggered manually
 * @author Phillip Kruger (phillip.kruger@gmail.com)
 */
@ApplicationScoped
public class ContinuousSyncService {
    @Inject
    NpmRegistryFacade npmRegistryFacade;
    @Inject
    CentralSyncService centralSyncService;
    @Inject
    FileStore fileStore;
    @Inject
    MavenFacade mavenFacade;
    
    private final ConcurrentLinkedQueue<NameVersionType> uploadQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RepoNameVersionType> statusQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RepoNameVersionType> releaseQueue = new ConcurrentLinkedQueue<>();
    
    private Optional<NameVersionType> uploadInProgress = Optional.empty();
    
    public List<RepoNameVersionType> getReleaseQueue(){
        return new ArrayList<>(releaseQueue);
    }
    
    public List<NameVersionType> getStagingQueue(){
        return new ArrayList<>(uploadQueue);
    }
    
    public boolean uploadInProgress(){
        return uploadInProgress.isPresent();
    }
    
    public NameVersionType getUploadInProgress(){
        return uploadInProgress.orElse(null);
    }
    
    /**
     * Check for version updates, and if a new version is out, do a sync
     */
    public void update(String groupId, String artifactId){
        Name name = NameParser.fromMavenGA(groupId, artifactId);
        update(name);
    }
    
    /**
     * Check for version updates, and if a new version is out, do a sync
     */
    public void update(Name name){
        Log.debug("====== mvnpm: Continuous Updater ======");
        Log.debug("\tChecking " + name.npmFullName());
        if(!isInternal(name)){
            // Get latest in NPM TODO: Later make this per patch release...
            Project project = npmRegistryFacade.getProject(name.npmFullName());
            if(project!=null){
                String latest = project.distTags().latest();
                addToSyncQueue(name, latest);
            }
        } else {
            Log.warn("TODO: !!!!!!! Handle internal " + name.mvnGroupId() + ":" + name.mvnArtifactId());
        }
    }
   
    /**
     * Sync a certain version of a artifact with central
     */
    public boolean addToSyncQueue(Name name, String version){
        NameVersionType itemInQ = new NameVersionType(name, version);
        SyncInfo syncInfo = centralSyncService.getSyncInfo(name.mvnGroupId(), name.mvnArtifactId(), version);
        if(syncInfo.canSync()){ // Check if this is already synced
            if(!uploadQueue.contains(itemInQ)){ // Already queued
                uploadQueue.add(itemInQ);
                return true;
            }
        }
        return false;
    }
    
    @Scheduled(every="10s")
    void processUploadQueue() {
        if(uploadInProgress.isEmpty()){
            if(!uploadQueue.isEmpty()){
                NameVersionType nav = uploadQueue.remove();
                uploadInProgress = Optional.of(nav);
                try {
                    String repoId = dequeueStagingUploads(nav.name(), nav.version());
                    if(repoId!=null){
                        statusQueue.add(new RepoNameVersionType(repoId, nav));
                    }
                }finally {
                    uploadInProgress = Optional.empty();
                }
            }else{
                Log.debug("Nothing in the queue to sync");
            }
        }else{
            Log.debug("Sync upload in progress " + uploadInProgress.get().name().displayName() + " " + uploadInProgress.get().version());
        }
    }
    
    @Scheduled(every="10s")
    void processStatusQueue() {
        if(!statusQueue.isEmpty()){
            RepoNameVersionType repoNameVersionType = statusQueue.remove();
            RepoStatus status = mavenFacade.status(repoNameVersionType.stagingRepoId());
            if(status.equals(RepoStatus.closed)){
                releaseQueue.add(repoNameVersionType);
            }else{
                statusQueue.add(repoNameVersionType);
            }
        }
    }
    
    @Scheduled(every="10s")
    void processReleaseQueue() {
        if(!releaseQueue.isEmpty()){
            RepoNameVersionType repoNameVersionType = releaseQueue.remove();
            boolean released = mavenFacade.release(repoNameVersionType.stagingRepoId());
            if(released){
                Name releasedArtifact = repoNameVersionType.nameVersionType().name();
                String version = repoNameVersionType.nameVersionType().version();
                Log.info("\t!! " + releasedArtifact.mvnGroupId()+":"+releasedArtifact.mvnArtifactId()+":"+version +" released !");
                // TODO: X
            }else{
                // TODO: Try again ?
            }
            
            
        }
    }
    
    private boolean isInternal(Name name){
        return name.mvnGroupId().equals("org.mvnpm.at.mvnpm");
    }
    
    private String dequeueStagingUploads(Name name, String version){
        SyncInfo syncInfo = centralSyncService.getSyncInfo(name.mvnGroupId(), name.mvnArtifactId(), version);
        if(syncInfo.canSync()){
            // Kick off an update
            Log.debug("Version [" + version + "] of " + name.npmFullName() + " is NOT in central. Kicking off sync...");
            return centralSyncService.sync(name, version);
        }else {
            Log.debug("Version [" + version + "] of " + name.npmFullName() + " already synced");
            return null;
        }
    }
    
    /**
     * Check all known artifacts for updates
     */
    @Scheduled(cron = "{mvnpm.cron.expr}", concurrentExecution = SKIP)
    @Blocking
    public void checkAll(){
        try {
            Log.debug("Starting full update check...");
            List<String[]> gavsToUpdate = findGaToUpdate();
        
            for(String[]ga:gavsToUpdate){
                update(ga[0], ga[1]);
            }
        }catch(Throwable t){
            Log.error(t.getMessage());
        }
    }
    
    public List<String[]> findGaToUpdate(){
        List<Path> artifactRoots = fileStore.getArtifactRoots();
        return artifactRoots.stream().map(this::toGroupArtifact)
                    .collect(Collectors.toUnmodifiableList());
        
    }

    private String[] toGroupArtifact(Path p) {
        String dir = p.toString();
        int i = dir.indexOf(Constants.SLASH_ORG_SLASH_MVNPM_SLASH);
        dir = dir.substring(i + 1);
        String[] parts = dir.split(Constants.SLASH);
        int l = parts.length;
        String artifactId = parts[l-1];
        String[] groupParts = Arrays.copyOf(parts, l-1);
        String groupId = String.join(".", groupParts);
        return new String[]{groupId, artifactId};
    }
    
}
