package com.louiekury.media.storage;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.validation.domain.MediaType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class S3MediaObjectStorageWireTest {
    @TempDir Path directory;

    @Test void sdkSendsConditionalPutAndDoesNotOverwriteOnConflict() throws Exception {
        byte[] original = {1, 2, 3};
        byte[] replacement = {9, 8, 7};
        var current = new AtomicReference<byte[]>();
        var requests = new AtomicInteger();
        var condition = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            condition.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            if (encoding != null && encoding.contains("aws-chunked")) body = decodeAwsChunks(body);
            int status = current.compareAndSet(null, body) ? 200 : 412;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try (var storage = storage(server)) {
            Path file = directory.resolve("upload");
            Files.write(file, original);
            storage.put("new-key", file, MediaType.PNG, original.length);
            Files.write(file, replacement);
            var failure = assertThrows(StorageException.class,
                    () -> storage.put("new-key", file, MediaType.PNG, replacement.length));
            assertEquals(StorageFailure.KEY_CONFLICT, failure.getFailure());
            assertEquals("*", condition.get());
            assertEquals(2, requests.get());
            assertArrayEquals(original, current.get());
        } finally { server.stop(0); }
    }

    @Test void lostResponseIsUnknownAndSdkDoesNotRetry() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        server.start();
        try (var storage = storage(server)) {
            Path file = directory.resolve("upload");
            Files.write(file, new byte[] {1, 2, 3});
            var failure = assertThrows(StorageException.class,
                    () -> storage.put("unknown-key", file, MediaType.PNG, 3));
            assertEquals(StorageFailure.STORAGE_OUTCOME_UNKNOWN, failure.getFailure());
            assertEquals("unknown-key", failure.getKey());
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test void distinguishesConditionalConflictRejectionAndUnknownOutcomes() throws Exception {
        for (Object[] sample : new Object[][] {
                {409, "STORAGE_CONCURRENT_CONFLICT"}, {412, "KEY_CONFLICT"},
                {403, "STORAGE_REJECTED"}, {408, "STORAGE_OUTCOME_UNKNOWN"},
                {500, "STORAGE_OUTCOME_UNKNOWN"}}) {
            int status = (int) sample[0];
            var requests = new AtomicInteger();
            var path = new AtomicReference<String>();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                path.set(exchange.getRequestURI().getPath());
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            try (var storage = storage(server)) {
                Path file = directory.resolve("upload-" + status);
                Files.write(file, new byte[] {1, 2, 3});
                var failure = assertThrows(StorageException.class,
                        () -> storage.put("original-key", file, MediaType.PNG, 3));
                assertEquals(sample[1], failure.getFailure().name(), "HTTP " + status);
                assertEquals("original-key", failure.getKey());
                assertEquals("/test-bucket/original-key", path.get());
                assertEquals(1, requests.get(), "HTTP " + status + " must not be retried");
            } finally { server.stop(0); }
        }
    }

    private static S3MediaObjectStorage storage(HttpServer server) {
        return new S3MediaObjectStorage(S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true), "test-bucket");
    }

    private static byte[] decodeAwsChunks(byte[] wire) throws IOException {
        var input = new ByteArrayInputStream(wire);
        var decoded = new ByteArrayOutputStream();
        while (true) {
            var line = new ByteArrayOutputStream();
            int previous = -1, current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') break;
                if (previous != -1) line.write(previous);
                previous = current;
            }
            if (current == -1) throw new IOException("Incomplete AWS chunk header");
            String header = line.toString(java.nio.charset.StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";", 2)[0], 16);
            if (size == 0) return decoded.toByteArray();
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Incomplete AWS chunk body");
            }
            decoded.write(chunk);
        }
    }
}
