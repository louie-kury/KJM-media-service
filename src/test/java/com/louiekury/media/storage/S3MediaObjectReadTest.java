package com.louiekury.media.storage;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class S3MediaObjectReadTest {
    @RegisterExtension
    static final S3MockExtension S3 = S3MockExtension.builder().silent()
            .withSecureConnection(false).build();

    @Test void readsDistinctObjectsAndEmptyObject() throws Exception {
        try (var client = S3.createS3ClientV2();
             var storage = storage(URI.create(S3.getServiceEndpoint()), "read-test-bucket")) {
            client.createBucket(b -> b.bucket("read-test-bucket"));
            for (byte[] bytes : new byte[][] {{1, 2, 3}, {9, 8}, {}}) {
                String key = "key-" + bytes.length;
                client.putObject(b -> b.bucket("read-test-bucket").key(key),
                        software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
                try (var stream = storage.get(key)) {
                    assertArrayEquals(bytes, stream.readAllBytes());
                }
            }
            var missing = assertThrows(StorageException.class, () -> storage.get("absent"));
            assertEquals(StorageFailure.OBJECT_NOT_FOUND, missing.getFailure());
        }
    }

    @Test void rejectsInvalidKeys() {
        try (var storage = storage(URI.create("http://127.0.0.1:1"), "bucket")) {
            assertThrows(IllegalArgumentException.class, () -> storage.get(null));
            assertThrows(IllegalArgumentException.class, () -> storage.get("  "));
        }
    }

    @Test void distinguishesExplicitNoSuchKeyFromOtherErrors() throws Exception {
        for (String[] sample : new String[][] {
                {"404", "NoSuchKey", "OBJECT_NOT_FOUND"},
                {"404", "NoSuchBucket", "STORAGE_READ_ERROR"},
                {"404", "", "STORAGE_READ_ERROR"},
                {"403", "AccessDenied", "STORAGE_READ_ERROR"},
                {"408", "RequestTimeout", "STORAGE_READ_ERROR"},
                {"500", "InternalError", "STORAGE_READ_ERROR"}}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = ("<Error><Code>" + sample[1] + "</Code></Error>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(Integer.parseInt(sample[0]), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try (var storage = storage(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "bucket")) {
                var error = assertThrows(StorageException.class, () -> storage.get("key"));
                assertEquals(sample[2], error.getFailure().name(), sample[0] + " " + sample[1]);
            } finally { server.stop(0); }
        }
    }

    @Test void connectionFailureIsNotReportedAsMissing() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.stop(0);
        try (var storage = storage(URI.create("http://127.0.0.1:" + port), "bucket")) {
            var error = assertThrows(StorageException.class, () -> storage.get("key"));
            assertEquals(StorageFailure.STORAGE_READ_ERROR, error.getFailure());
        }
    }

    @Test void incompleteBodyFailsDuringRead() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[] {1, 2, 3});
            try { exchange.close(); } catch (Exception ignored) { }
        });
        server.start();
        try (var storage = storage(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "bucket");
             var stream = storage.get("key")) {
            assertThrows(IOException.class, stream::readAllBytes);
        } finally { server.stop(0); }
    }

    @Test void returnsStreamBeforeLargeResponseCompletesAndCallerCanCloseEarly() throws Exception {
        var releaseBody = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024 * 1024);
                exchange.getResponseBody().write(42);
                exchange.getResponseBody().flush();
                releaseBody.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(new byte[1024 * 1024 - 1]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        var executor = Executors.newSingleThreadExecutor();
        try (var storage = new S3MediaObjectStorage(S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(software.amazon.awssdk.http.apache.ApacheHttpClient.builder().maxConnections(1)), "bucket")) {
            var pending = executor.submit(() -> storage.get("large"));
            var stream = pending.get(3, TimeUnit.SECONDS);
            try (stream) {
                assertEquals(42, stream.read());
                releaseBody.countDown();
            }
            try (var second = storage.get("another")) {
                assertEquals(42, second.read());
                second.readAllBytes();
            }
        } finally {
            releaseBody.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test void delayedResponseTimesOutAsStorageError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var storage = new S3MediaObjectStorage(S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofMillis(300))), "bucket")) {
            var error = assertThrows(StorageException.class, () -> storage.get("key"));
            assertEquals(StorageFailure.STORAGE_READ_ERROR, error.getFailure());
        } finally { release.countDown(); server.stop(0); }
    }

    private static S3MediaObjectStorage storage(URI endpoint, String bucket) {
        return new S3MediaObjectStorage(S3Client.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))), bucket);
    }
}
