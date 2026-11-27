package com.louiekury.media.storage;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.validation.domain.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class S3MockStorageIntegrationTest {
    @RegisterExtension
    static final S3MockExtension S3 = S3MockExtension.builder().silent()
            .withSecureConnection(false).build();
    @TempDir Path directory;

    @Test void conditionalPutPreservesOriginalObjectOnConflict() throws Exception {
        byte[] original = {1, 2, 3};
        byte[] replacement = {4, 5, 6};
        Path file = directory.resolve("upload.png");
        try (S3Client client = S3.createS3ClientV2();
             S3MediaObjectStorage storage = new S3MediaObjectStorage(S3Client.builder()
                     .endpointOverride(URI.create(S3.getServiceEndpoint()))
                     .region(Region.US_EAST_1).forcePathStyle(true)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))),
                     "media-test-bucket")) {
            client.createBucket(b -> b.bucket("media-test-bucket"));
            Files.write(file, original);
            storage.put("test-file.png", file, MediaType.PNG, original.length);
            assertArrayEquals(original, client.getObjectAsBytes(b -> b.bucket("media-test-bucket").key("test-file.png")).asByteArray());
            Files.write(file, replacement);
            var error = assertThrows(StorageException.class,
                    () -> storage.put("test-file.png", file, MediaType.PNG, replacement.length));
            assertEquals(StorageFailure.KEY_CONFLICT, error.getFailure());
            assertArrayEquals(original, client.getObjectAsBytes(b -> b.bucket("media-test-bucket").key("test-file.png")).asByteArray());
        }
    }
}
