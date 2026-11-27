package com.louiekury.media.storage.application;

import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.storage.application.port.out.TemporaryMediaFiles;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

public class MediaStorageService {
    private final MediaValidationService validator;
    private final MediaObjectStorage storage;
    private final TemporaryMediaFiles temporaryFiles;

    public MediaStorageService(MediaValidationService validator, MediaObjectStorage storage, TemporaryMediaFiles temporaryFiles) {
        this.validator = Objects.requireNonNull(validator);
        this.storage = Objects.requireNonNull(storage);
        this.temporaryFiles = Objects.requireNonNull(temporaryFiles);
    }

    /** 입력 스트림은 호출자가 닫습니다. */
    public StoredObject store(MediaUsage usage, String filename, String contentType, InputStream input, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        Path file;
        try { file = temporaryFiles.create(); }
        catch (IOException error) { throw new StorageException(StorageFailure.TEMPORARY_FILE_ERROR, key, error); }
        StoredObject result = null;
        RuntimeException failure = null;
        try {
            ValidatedMedia validated;
            try (OutputStream output = temporaryFiles.open(file)) {
                validated = validator.validate(usage, filename, contentType, input, output);
            } catch (IOException error) {
                throw new StorageException(StorageFailure.TEMPORARY_FILE_ERROR, key, error);
            }
            storage.put(key, file, validated.getType(), validated.getSize());
            result = new StoredObject(key, validated.getType(), validated.getSize());
        } catch (RuntimeException error) { failure = error; }
        try { temporaryFiles.delete(file); }
        catch (IOException error) {
            if (failure != null) failure.addSuppressed(error);
            else throw new StoredCleanupException(result, error);
        }
        if (failure != null) throw failure;
        return result;
    }
}
