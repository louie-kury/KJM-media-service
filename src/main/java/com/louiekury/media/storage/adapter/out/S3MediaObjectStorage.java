package com.louiekury.media.storage.adapter.out;

import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.port.out.MediaObjectReader;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.validation.domain.MediaType;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class S3MediaObjectStorage implements MediaObjectStorage, MediaObjectReader, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    public S3MediaObjectStorage(S3ClientBuilder builder, String bucket) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("bucket is required");
        this.bucket = bucket;
        this.client = Objects.requireNonNull(builder)
                .overrideConfiguration(c -> c.retryStrategy(r -> r.maxAttempts(1))).build();
    }

    @Override
    public InputStream get(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception error) {
            String code = error.awsErrorDetails() == null ? null : error.awsErrorDetails().errorCode();
            StorageFailure failure = error.statusCode() == 404 && "NoSuchKey".equals(code)
                    ? StorageFailure.OBJECT_NOT_FOUND : StorageFailure.STORAGE_READ_ERROR;
            throw new StorageException(failure, key, error);
        } catch (SdkClientException error) {
            throw new StorageException(StorageFailure.STORAGE_READ_ERROR, key, error);
        }
    }

    @Override
    public void put(String key, Path file, MediaType type, long size) {
        PutObjectRequest request = PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(key)
        .ifNoneMatch("*")
        .contentType(type.getMimeType())
        .contentLength(size)
        .build();
        try {
            client.putObject(request, RequestBody.fromFile(file));
        } catch (S3Exception error) {
            int status = error.statusCode();
            StorageFailure failure = status == 412 ? StorageFailure.KEY_CONFLICT
                    : status == 409 ? StorageFailure.STORAGE_CONCURRENT_CONFLICT
                    : (status >= 400 && status < 500 && status != 408)
                    ? StorageFailure.STORAGE_REJECTED : StorageFailure.STORAGE_OUTCOME_UNKNOWN;
            throw new StorageException(failure, key, error);
        } catch (SdkClientException error) {
            throw new StorageException(StorageFailure.STORAGE_OUTCOME_UNKNOWN, key, error);
        }
    }

    @Override
    public void close() { client.close(); }
}
