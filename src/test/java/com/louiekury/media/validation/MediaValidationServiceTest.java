package com.louiekury.media.validation;

import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MediaValidationServiceTest {
    private final MediaValidationService service = new MediaValidationService(new FileSignatureDetector());
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 8, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] MP4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm'};

    @Test void acceptsImagesForEveryUsageAndMp4ForPost() {
        for (MediaUsage usage : MediaUsage.values()) {
            valid(usage, "a.jpg", "image/jpeg", JPEG, MediaType.JPEG);
            valid(usage, "a.png", "image/png", PNG, MediaType.PNG);
            valid(usage, "a.webp", "image/webp", WEBP, MediaType.WEBP);
        }
        valid(MediaUsage.POST_ATTACHMENT, "a.mp4", "video/mp4", MP4, MediaType.MP4);
        for (MediaUsage usage : MediaUsage.values()) {
            if (usage != MediaUsage.POST_ATTACHMENT) failure(ValidationFailure.UNSUPPORTED_TYPE, usage, "a.mp4", "video/mp4", MP4);
        }
    }

    @Test void acceptsExactLimitsAndRejectsOneExtraByte() {
        for (Object[] sample : new Object[][] {
                {"a.jpg", "image/jpeg", JPEG, 10_000_000L},
                {"a.png", "image/png", PNG, 10_000_000L},
                {"a.webp", "image/webp", WEBP, 10_000_000L},
                {"a.mp4", "video/mp4", MP4, 100_000_000L}}) {
            String name = (String) sample[0], mime = (String) sample[1];
            byte[] prefix = (byte[]) sample[2]; long limit = (long) sample[3];
            var result = service.validate(MediaUsage.POST_ATTACHMENT, name, mime, sized(prefix, limit), OutputStream.nullOutputStream());
            assertEquals(limit, result.getSize());
            var ex = assertThrows(MediaValidationException.class, () -> service.validate(
                    MediaUsage.POST_ATTACHMENT, name, mime, sized(prefix, limit + 1), OutputStream.nullOutputStream()));
            assertEquals(ValidationFailure.FILE_TOO_LARGE, ex.getReason());
        }
    }

    @Test void rejectsEmptyTruncatedUnknownAndConflictingInputs() {
        failure(ValidationFailure.UNSUPPORTED_USAGE, null, "a.jpg", "image/jpeg", JPEG);
        failure(ValidationFailure.UNSUPPORTED_TYPE, MediaUsage.POST_ATTACHMENT, "a.gif", "image/gif", JPEG);
        failure(ValidationFailure.UNSUPPORTED_TYPE, MediaUsage.POST_ATTACHMENT, "a.jpg", "image/jpeg; invalid", JPEG);
        failure(ValidationFailure.TYPE_MISMATCH, MediaUsage.POST_ATTACHMENT, "a.jpg", "image/png", JPEG);
        failure(ValidationFailure.TYPE_MISMATCH, MediaUsage.POST_ATTACHMENT, "a.jpg", "image/jpeg", PNG);
        failure(ValidationFailure.EMPTY_FILE, MediaUsage.POST_ATTACHMENT, "a.jpg", "image/jpeg", new byte[0]);
        failure(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "a.png", "image/png", Arrays.copyOf(PNG, 7));
        failure(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "a.mp4", "video/mp4", Arrays.copyOf(MP4, 16));
    }

    @Test void handlesFragmentedAndZeroLengthReads() {
        InputStream in = new ByteArrayInputStream(PNG) {
            private boolean first = true;
            @Override public synchronized int read(byte[] b, int off, int len) {
                if (first) { first = false; return 0; }
                return super.read(b, off, Math.min(len, 1));
            }
        };
        var out = new ByteArrayOutputStream();
        var result = service.validate(MediaUsage.POST_ATTACHMENT, "a.png", "image/png", in, out);
        assertEquals(PNG.length, result.getSize());
        assertArrayEquals(PNG, out.toByteArray());
    }

    @Test void distinguishesReadAndWriteFailures() {
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("read"); } };
        var read = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.jpg", "image/jpeg", broken, OutputStream.nullOutputStream()));
        assertEquals(ValidationFailure.READ_ERROR, read.getReason());
        OutputStream brokenOutput = new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("write"); } };
        var write = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.jpg", "image/jpeg", new ByteArrayInputStream(JPEG), brokenOutput));
        assertEquals(ValidationFailure.WRITE_ERROR, write.getReason());
    }

    @Test void acceptsMp4WithFileTypeBoxLargerThanReadBuffer() {
        byte[] box = new byte[4100];
        System.arraycopy(MP4, 0, box, 0, MP4.length);
        box[0] = 0; box[1] = 0; box[2] = 16; box[3] = 4;
        valid(MediaUsage.POST_ATTACHMENT, "large-ftyp.mp4", "video/mp4", box, MediaType.MP4);
    }

    private static final byte[] AUDIO_MP4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0, 'm', 'p', '4', '2'};

    @Test void rejectsAudioMajorBrandInDirectDetection() {
        var detector = new FileSignatureDetector();
        assertNull(detector.detect(AUDIO_MP4, AUDIO_MP4.length));
    }

    @Test void rejectsAudioMajorBrandInFragmentedScanner() {
        var scanner = new FileSignatureDetector().mp4Scanner();
        for (byte value : AUDIO_MP4) scanner.accept(new byte[] {value}, 1);
        assertNull(scanner.result());
    }

    @Test void rejectsAudioMajorBrandInService() {
        failure(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "audio.mp4", "video/mp4", AUDIO_MP4);
    }

    @Test void rejectsInvalidFileTypeBoxWithoutReadingOrWritingWholeFile() {
        for (byte[] invalid : new byte[][] {
                {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0},
                {0, 0, 0, 20, 'n', 'o', 'p', 'e', 'i', 's', 'o', 'm', 0, 0, 0, 0}}) {
            rejectsInvalidFileTypeBoxWithoutReadingOrWritingWholeFile(invalid);
        }
    }

    private void rejectsInvalidFileTypeBoxWithoutReadingOrWritingWholeFile(byte[] invalid) {
        InputStream source = sized(invalid, 10_000_000);
        long[] read = {0}, written = {0};
        InputStream counted = new FilterInputStream(source) {
            @Override public int read() throws IOException { int value = in.read(); if (value >= 0) read[0]++; return value; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len); if (n > 0) read[0] += n; return n;
            }
        };
        OutputStream output = new OutputStream() {
            @Override public void write(int b) { written[0]++; }
            @Override public void write(byte[] b, int off, int len) { written[0] += len; }
        };
        var error = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "invalid.mp4", "video/mp4", counted, output));
        assertEquals(ValidationFailure.UNKNOWN_CONTENT, error.getReason());
        assertTrue(read[0] <= 4096, "확실히 잘못된 헤더를 확인한 후 파일 전체를 읽으면 안 됩니다");
        assertTrue(written[0] <= 4096, "잘못된 파일 전체를 임시 출력에 기록하면 안 됩니다");
    }

    @Test void rejectsTruncatedLargeFileTypeBoxAndImageBrandBeyondReadBuffer() {
        byte[] box = new byte[4100];
        System.arraycopy(MP4, 0, box, 0, MP4.length);
        box[2] = 16; box[3] = 4;
        failure(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "a.mp4", "video/mp4", Arrays.copyOf(box, 4096));
        System.arraycopy(new byte[] {'h', 'e', 'i', 'c'}, 0, box, 4096, 4);
        failure(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "a.mp4", "video/mp4", box);
    }

    @Test void rejectsOversizeDeclaredNormalFileTypeBoxEarly() {
        assertOversizeFileTypeBoxRejectedEarly(false);
    }

    @Test void rejectsOversizeDeclaredExtendedFileTypeBoxEarlyWithFragmentedHeader() {
        assertOversizeFileTypeBoxRejectedEarly(true);
    }

    @Test void acceptsFileTypeBoxAtVideoSizeLimit() {
        for (boolean extended : new boolean[] {false, true}) {
            var result = service.validate(MediaUsage.POST_ATTACHMENT, "a.mp4", "video/mp4",
                    sized(fileTypeBox(100_000_000L, extended), 100_000_000L), OutputStream.nullOutputStream());
            assertEquals(100_000_000L, result.getSize());
        }
    }

    private void assertOversizeFileTypeBoxRejectedEarly(boolean extended) {
        InputStream source = sized(fileTypeBox(100_000_004L, extended), 100_000_004L);
        long[] read = {0}, written = {0};
        InputStream counted = new FilterInputStream(source) {
            @Override public int read() throws IOException { int value = in.read(); if (value >= 0) read[0]++; return value; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, extended ? Math.min(len, 3) : len);
                if (n > 0) read[0] += n;
                return n;
            }
        };
        OutputStream output = new OutputStream() {
            @Override public void write(int b) { written[0]++; }
            @Override public void write(byte[] b, int off, int len) { written[0] += len; }
        };
        assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.mp4", "video/mp4", counted, output));
        assertTrue(read[0] <= 4096, "파일 제한을 초과하는 ftyp 크기 확인 후 추가로 읽으면 안 됩니다");
        assertEquals(0, written[0], "파일 제한을 초과하는 ftyp는 임시 출력에 쓰면 안 됩니다");
    }

    private byte[] fileTypeBox(long size, boolean extended) {
        var bytes = java.nio.ByteBuffer.allocate(extended ? 24 : 16);
        bytes.putInt(extended ? 1 : (int) size).put(new byte[] {'f', 't', 'y', 'p'});
        if (extended) bytes.putLong(size);
        bytes.put(new byte[] {'i', 's', 'o', 'm'}).putInt(0);
        return bytes.array();
    }

    @Test void rejectsOversizeAfterWritingPartialTemporaryOutput() {
        var out = new ByteArrayOutputStream();
        var ex = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.jpg", "image/jpeg", sized(JPEG, 10_000_001), out));
        assertEquals(ValidationFailure.FILE_TOO_LARGE, ex.getReason());
        assertTrue(out.size() > 0, "실패해도 임시 출력이 존재하므로 호출자가 폐기해야 합니다");
    }

    @Test void rejectsReadFailureAfterWritingPartialTemporaryOutput() {
        InputStream broken = new InputStream() {
            private final InputStream first = sized(JPEG, 4096);
            @Override public int read() throws IOException { int value = first.read(); if (value < 0) throw new IOException("read"); return value; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int count = first.read(b, off, len); if (count < 0) throw new IOException("read"); return count;
            }
        };
        var out = new ByteArrayOutputStream();
        var ex = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.jpg", "image/jpeg", broken, out));
        assertEquals(ValidationFailure.READ_ERROR, ex.getReason());
        assertTrue(out.size() > 0, "실패해도 임시 출력이 존재하므로 호출자가 폐기해야 합니다");
    }

    @Test void rejectsWriteFailureAfterWritingPartialTemporaryOutput() {
        var out = new ByteArrayOutputStream();
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("write"); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (out.size() != 0) throw new IOException("write");
                out.write(b, off, len);
            }
        };
        var ex = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "a.jpg", "image/jpeg", sized(JPEG, 8192), broken));
        assertEquals(ValidationFailure.WRITE_ERROR, ex.getReason());
        assertTrue(out.size() > 0, "실패해도 임시 출력이 존재하므로 호출자가 폐기해야 합니다");
    }

    @Test void exampleCallerDiscardsFailureAndPromotesOnlySuccess(@TempDir Path directory) throws IOException {
        Path temporary = directory.resolve("upload.tmp");
        Path completed = directory.resolve("upload.jpg");
        assertThrows(MediaValidationException.class, () -> exampleCaller(temporary, completed, sized(JPEG, 10_000_001)));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(completed));

        exampleCaller(temporary, completed, new ByteArrayInputStream(JPEG));
        assertFalse(Files.exists(temporary));
        assertArrayEquals(JPEG, Files.readAllBytes(completed));
    }

    // 테스트 전용 호출자: 실제 서비스의 임시 파일 폐기 구현 여부를 뜻하지 않습니다.
    private void exampleCaller(Path temporary, Path completed, InputStream input) throws IOException {
        try {
            try (var output = Files.newOutputStream(temporary)) {
                service.validate(MediaUsage.POST_ATTACHMENT, "a.jpg", "image/jpeg", input, output);
            }
            Files.move(temporary, completed);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void valid(MediaUsage usage, String name, String mime, byte[] bytes, MediaType type) {
        var out = new ByteArrayOutputStream();
        var result = service.validate(usage, name, mime, new ByteArrayInputStream(bytes), out);
        assertEquals(type, result.getType());
        assertEquals(bytes.length, result.getSize());
        assertArrayEquals(bytes, out.toByteArray());
    }

    private void failure(ValidationFailure expected, MediaUsage usage, String name, String mime, byte[] bytes) {
        var ex = assertThrows(MediaValidationException.class, () -> service.validate(usage, name, mime,
                new ByteArrayInputStream(bytes), OutputStream.nullOutputStream()));
        assertEquals(expected, ex.getReason());
    }

    private InputStream sized(byte[] prefix, long size) {
        return new InputStream() {
            long offset;
            @Override public int read() { return offset >= size ? -1 : offset < prefix.length ? prefix[(int) offset++] & 255 : advance(); }
            private int advance() { offset++; return 0; }
            @Override public int read(byte[] b, int off, int len) {
                if (offset >= size) return -1;
                int count = (int) Math.min(len, size - offset);
                int copied = (int) Math.min(count, Math.max(0, prefix.length - offset));
                if (copied > 0) System.arraycopy(prefix, (int) offset, b, off, copied);
                Arrays.fill(b, off + copied, off + count, (byte) 0);
                offset += count;
                return count;
            }
        };
    }
}
