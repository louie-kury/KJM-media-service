package com.louiekury.media.storage.application.model;

import com.louiekury.media.validation.domain.MediaType;

public final class StoredObject {
    private final String key;
    private final MediaType type;
    private final long size;

    public StoredObject(String key, MediaType type, long size) {
        this.key = key;
        this.type = type;
        this.size = size;
    }

    public String key() { return key; }
    public MediaType type() { return type; }
    public long size() { return size; }
}
