package com.louiekury.media.storage.application.port.out;

import com.louiekury.media.validation.domain.MediaType;
import java.nio.file.Path;

public interface MediaObjectStorage {
    void put(String key, Path file, MediaType type, long size);
}
