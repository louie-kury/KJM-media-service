package com.louiekury.media.validation.application.service;

import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.port.out.MediaContentDetector;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** 성공 전 출력은 임시 데이터이며 호출자가 실패 시 폐기하고 스트림을 닫습니다. */
public class MediaValidationService {
    private final MediaContentDetector detector;

    public MediaValidationService(MediaContentDetector detector) {
        this.detector = Objects.requireNonNull(detector);
    }

    public ValidatedMedia validate(MediaUsage usage, String filename, String contentType,
                                   InputStream input, OutputStream stagedOutput) {
        if (usage == null) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_USAGE);
        MediaType filenameType = MediaType.fromFilename(filename);
        MediaType declaredType = MediaType.fromContentType(contentType);
        if (filenameType == null || declaredType == null) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_TYPE);
        if (filenameType != declaredType) throw new MediaValidationException(ValidationFailure.TYPE_MISMATCH);
        if (!usage.allows(filenameType)) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_TYPE);
        if (input == null) throw new MediaValidationException(ValidationFailure.READ_ERROR);
        if (stagedOutput == null) throw new MediaValidationException(ValidationFailure.WRITE_ERROR);

        byte[] prefix = new byte[4096];
        int length = 0;
        boolean eof = false;
        while (length < prefix.length) {
            int count = read(input, prefix, length, prefix.length - length);
            if (count < 0) { eof = true; break; }
            length += count;
        }
        if (length == 0) throw new MediaValidationException(ValidationFailure.EMPTY_FILE);
        MediaType actualType = detector.detect(prefix, length);
        if (actualType != null && actualType != filenameType) throw new MediaValidationException(ValidationFailure.TYPE_MISMATCH);
        MediaContentDetector.Mp4Scanner mp4 = filenameType == MediaType.MP4 ? detector.mp4Scanner() : null;
        if (mp4 == null && actualType == null) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
        if (mp4 != null) {
            mp4.accept(prefix, length);
            if (mp4.rejected()) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
        }

        long size = length;
        write(stagedOutput, prefix, length);
        byte[] buffer = new byte[8192];
        while (!eof) {
            int count = read(input, buffer, 0, (int) Math.min(buffer.length, filenameType.getMaxBytes() + 1 - size));
            if (count < 0) break;
            size += count;
            if (size > filenameType.getMaxBytes()) throw new MediaValidationException(ValidationFailure.FILE_TOO_LARGE);
            if (mp4 != null) {
                mp4.accept(buffer, count);
                if (mp4.rejected()) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
            }
            write(stagedOutput, buffer, count);
        }
        if (mp4 != null && mp4.result() == null) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
        return new ValidatedMedia(filenameType, size);
    }

    private static int read(InputStream input, byte[] buffer, int offset, int length) {
        try {
            int count = input.read(buffer, offset, length);
            if (count == 0) {
                int one = input.read();
                if (one < 0) return -1;
                buffer[offset] = (byte) one;
                return 1;
            }
            return count;
        } catch (IOException error) {
            throw new MediaValidationException(ValidationFailure.READ_ERROR, error);
        }
    }

    private static void write(OutputStream output, byte[] buffer, int count) {
        try {
            output.write(buffer, 0, count);
        } catch (IOException error) {
            throw new MediaValidationException(ValidationFailure.WRITE_ERROR, error);
        }
    }
}
