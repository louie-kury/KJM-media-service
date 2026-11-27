package com.louiekury.media.storage.application;

import com.louiekury.media.storage.application.model.StoredObject;

public class StoredCleanupException extends RuntimeException {
    private final StoredObject storedObject;
    public StoredCleanupException(StoredObject storedObject, Throwable cause) {
        super("Stored object, but temporary file cleanup failed", cause);
        this.storedObject = storedObject;
    }
    public StoredObject getStoredObject() { return storedObject; }
}
