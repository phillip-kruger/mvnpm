package io.mvnpm.file;

/**
 * Event that fires when a file has been created
 * @author Phillip Kruger (phillip.kruger@gmail.com)
 */
public record FileStoreEvent(io.mvnpm.npm.model.Package p, String fileName) {}