package com.louiekury.media.storage.application;

public class StorageException extends RuntimeException {
    private final StorageFailure failure;
    private final String key;

    public StorageException(StorageFailure failure, String key) { this(failure, key, null); }
    public StorageException(StorageFailure failure, String key, Throwable cause) {
        super(failure.name(), cause);
        this.failure = failure;
        this.key = key;
    }
    public StorageFailure getFailure() { return failure; }
    public String getKey() { return key; }
}
