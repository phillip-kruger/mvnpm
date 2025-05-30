package io.mvnpm.mavencentral.sync;

import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.mvnpm.mavencentral.MavenCentralFacade;
import io.mvnpm.mavencentral.exceptions.MissingFilesForBundleException;
import io.mvnpm.mavencentral.exceptions.UploadFailedException;
import io.mvnpm.npm.NpmRegistryFacade;
import io.mvnpm.npm.model.Name;
import io.mvnpm.npm.model.NameParser;
import io.mvnpm.npm.model.Project;

/**
 * This sync a package with maven central
 *
 * @author Phillip Kruger (phillip.kruger@gmail.com)
 */
@ApplicationScoped
public class CentralSyncService {
    @Inject
    BundleCreator bundleCreator;

    @Inject
    MavenCentralFacade mavenCentralFacade;

    @Inject
    NpmRegistryFacade npmRegistryFacade;

    @Inject
    CentralSyncItemService centralSyncItemService;

    @Transactional
    public CentralSyncItem checkReleaseInDbAndCentral(String groupId, String artifactId, String version, boolean startSync) {
        if ("latest".equalsIgnoreCase(version)) {
            version = getLatestVersion(groupId, artifactId);
        }

        CentralSyncItem centralSyncItem = centralSyncItemService.findOrCreate(groupId, artifactId, version,
                startSync ? Stage.PACKAGING : Stage.NONE);

        // Check the status
        if (!centralSyncItem.alreadyReleased() && checkCentralStatusAndUpdateStageIfNeeded(centralSyncItem)) {
            centralSyncItem.stage = Stage.RELEASED;
            centralSyncItemService.merge(centralSyncItem);
        } else if (startSync && centralSyncItem.stage == Stage.NONE) {
            centralSyncItem = centralSyncItemService.changeStage(centralSyncItem, Stage.PACKAGING);
        }
        return centralSyncItem;
    }

    @Transactional
    public boolean initializeSync(Name name, String version) {
        return initializeSync(name.mvnGroupId, name.mvnArtifactId, version);
    }

    /**
     * Sync a certain version of a artifact with central
     */
    private boolean initializeSync(String groupId, String artifactId, String version) {
        CentralSyncItem itemToSync = centralSyncItemService.findOrCreate(groupId, artifactId, version, Stage.INIT);
        if (itemToSync.stage == Stage.INIT) {
            // Already started
            return false;
        }
        if (canProcessSync(itemToSync)) { // Check if this is already synced or in progress
            itemToSync = centralSyncItemService.changeStage(itemToSync, Stage.INIT);
            return true;
        }
        return false;
    }

    /**
     * Check if this is not already in Central, or in the process of being synced
     */
    public boolean canProcessSync(CentralSyncItem csi) {
        if (csi.alreadyReleased()) {
            csi = centralSyncItemService.changeStage(csi, Stage.RELEASED);
            return false;
        }
        if (csi.isInProgress() || csi.isInError()) {
            checkCentralStatusAndUpdateStageIfNeeded(csi); // Clear the queue
            return false;
        }
        // Next try remote (might have been synced before we stored)
        return !checkCentralStatusAndUpdateStageIfNeeded(csi);
    }

    public boolean checkCentralStatusAndUpdateStageIfNeeded(CentralSyncItem csi) {
        boolean isPublishedInCentral = mavenCentralFacade.isInCentral(csi.groupId,
                csi.artifactId, csi.version);
        if (isPublishedInCentral) {
            csi = centralSyncItemService.changeStage(csi, Stage.RELEASED);
        }
        return isPublishedInCentral;
    }

    public String sync(CentralSyncItem centralSyncItem) throws UploadFailedException, MissingFilesForBundleException {
        return sync(centralSyncItem.groupId,
                centralSyncItem.artifactId,
                centralSyncItem.version);
    }

    public String sync(String groupId, String artifactId, String version)
            throws UploadFailedException, MissingFilesForBundleException {
        Path bundlePath = bundleCreator.bundle(groupId, artifactId, version);
        return mavenCentralFacade.upload(bundlePath);
    }

    public String getLatestVersion(String groupId, String artifactId) {
        Name name = NameParser.fromMavenGA(groupId, artifactId);
        return getLatestVersion(name);
    }

    public String getLatestVersion(Name fullName) {
        Project project = npmRegistryFacade.getProject(fullName.npmFullName);
        return project.distTags().latest();
    }
}
