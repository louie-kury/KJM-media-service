package com.louiekury.media.storage;

import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.StoredCleanupException;
import com.louiekury.media.storage.application.port.out.TemporaryMediaFiles;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MediaStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    @TempDir Path directory;

    @Test void storesOnlyAfterValidationAndCleansTemporaryFile() {
        var temp = new TemporaryFiles();
        var service = new MediaStorageService(validator(), (key, path, type, size) -> {
            assertEquals("fresh-key", key);
            assertEquals(MediaType.PNG, type);
            assertEquals(PNG.length, size);
            try { assertArrayEquals(PNG, Files.readAllBytes(path)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }, temp);
        var result = service.store(MediaUsage.POST_ATTACHMENT, "a.png", "image/png", input(PNG), "fresh-key");
        assertEquals("fresh-key", result.key());
        assertEquals(MediaType.PNG, result.type());
        assertEquals(PNG.length, result.size());
        assertFalse(Files.exists(temp.path));
    }

    @Test void rejectsEmptyAndMismatchedFilesWithoutStorageRequest() {
        for (byte[] bytes : new byte[][] {new byte[0], {1, 2, 3}}) {
            var temp = new TemporaryFiles();
            var calls = new AtomicInteger();
            var service = new MediaStorageService(validator(), (k, p, t, s) -> calls.incrementAndGet(), temp);
            assertThrows(MediaValidationException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                    "a.png", "image/png", input(bytes), "key"));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(temp.path));
        }
    }

    @Test void storageFailureStillCleansUpAndPreservesFailure() {
        for (StorageFailure failure : new StorageFailure[] {StorageFailure.KEY_CONFLICT,
                StorageFailure.STORAGE_REJECTED, StorageFailure.STORAGE_OUTCOME_UNKNOWN}) {
            var temp = new TemporaryFiles();
            var calls = new AtomicInteger();
            var service = new MediaStorageService(validator(), (k, p, t, s) -> {
                calls.incrementAndGet();
                throw new StorageException(failure, k);
            }, temp);
            var error = assertThrows(StorageException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                    "a.png", "image/png", input(PNG), "key"));
            assertEquals(failure, error.getFailure());
            assertEquals("key", error.getKey());
            assertEquals(1, calls.get());
            assertFalse(Files.exists(temp.path));
        }
    }

    @Test void reportsStoredResultWhenCleanupFails() {
        var temp = new TemporaryFiles();
        temp.failDelete = true;
        var service = new MediaStorageService(validator(), (k, p, t, s) -> {}, temp);
        var error = assertThrows(StoredCleanupException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                "a.png", "image/png", input(PNG), "key"));
        assertEquals("key", error.getStoredObject().key());
        assertEquals(PNG.length, error.getStoredObject().size());
    }

    @Test void preservesValidationFailureWhenCleanupAlsoFails() {
        var temp = new TemporaryFiles();
        temp.failDelete = true;
        var service = new MediaStorageService(validator(), (k, p, t, s) -> fail("unexpected PUT"), temp);
        var error = assertThrows(MediaValidationException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                "a.png", "image/png", input(new byte[0]), "key"));
        assertTrue(error.getSuppressed().length > 0);
    }

    @Test void closeFailurePreventsStorageAndStillCleansUp() {
        var temp = new TemporaryFiles();
        temp.failClose = true;
        var service = new MediaStorageService(validator(), (k, p, t, s) -> fail("unexpected PUT"), temp);
        assertThrows(RuntimeException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                "a.png", "image/png", input(PNG), "key"));
        assertFalse(Files.exists(temp.path));
    }

    @Test void temporaryCreateAndWriteFailuresPreventStorage() {
        for (String phase : new String[] {"create", "open", "write"}) {
            var temp = new TemporaryFiles();
            temp.failPhase = phase;
            var service = new MediaStorageService(validator(), (k, p, t, s) -> fail("unexpected PUT"), temp);
            assertThrows(RuntimeException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                    "a.png", "image/png", input(PNG), "key"));
            if (temp.path != null) assertFalse(Files.exists(temp.path));
        }
    }

    @Test void exactImageLimitIsStoredButOneByteOverIsRejected() {
        for (int extra : new int[] {0, 1}) {
            var temp = new TemporaryFiles();
            var calls = new AtomicInteger();
            var service = new MediaStorageService(validator(), (k, p, t, s) -> {
                calls.incrementAndGet();
                assertEquals(10_000_000, s);
            }, temp);
            byte[] image = new byte[10_000_000 + extra];
            System.arraycopy(PNG, 0, image, 0, PNG.length);
            if (extra == 0) service.store(MediaUsage.POST_ATTACHMENT, "a.png", "image/png", input(image), "key");
            else assertThrows(MediaValidationException.class, () -> service.store(MediaUsage.POST_ATTACHMENT,
                    "a.png", "image/png", input(image), "key"));
            assertEquals(1 - extra, calls.get());
            assertFalse(Files.exists(temp.path));
        }
    }

    private static MediaValidationService validator() { return new MediaValidationService(new FileSignatureDetector()); }
    private static InputStream input(byte[] bytes) { return new ByteArrayInputStream(bytes); }

    private class TemporaryFiles implements TemporaryMediaFiles {
        Path path;
        boolean failDelete;
        boolean failClose;
        String failPhase;
        public Path create() throws IOException {
            if ("create".equals(failPhase)) throw new IOException("create failed");
            return path = Files.createTempFile(directory, "media-", ".tmp");
        }
        public OutputStream open(Path value) throws IOException {
            if ("open".equals(failPhase)) throw new IOException("open failed");
            return new FilterOutputStream(Files.newOutputStream(value)) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if ("write".equals(failPhase)) throw new IOException("write failed");
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    super.close();
                    if (failClose) throw new IOException("close failed");
                }
            };
        }
        public void delete(Path value) throws IOException {
            if (failDelete) throw new IOException("delete failed");
            Files.deleteIfExists(value);
        }
    }
}
