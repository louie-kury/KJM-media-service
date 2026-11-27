package com.louiekury.media.validation.adapter.out;

import com.louiekury.media.validation.application.port.out.MediaContentDetector;
import com.louiekury.media.validation.domain.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 파일의 기본 식별 정보만 검사합니다. 파일 전체의 안전성은 보장하지 않습니다. */
public class FileSignatureDetector implements MediaContentDetector {
    private static final Set<String> IMAGE_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis");
    private static final Set<String> MP4_BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso8", "iso9", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash");

    @Override
    public Mp4Scanner mp4Scanner() {
        return new Mp4Scanner() {
            private final byte[] header = new byte[24];
            private final byte[] brand = new byte[4];
            private long position;
            private long boxSize = -1;
            private int headerSize = 8;
            private boolean invalid;
            private boolean mp4;

            @Override
            public void accept(byte[] bytes, int length) {
                for (int i = 0; i < length; i++) {
                    byte value = bytes[i];
                    if (position < header.length) header[(int) position] = value;
                    position++;
                    if (position == 8) {
                        if (!matches(header, 4, "ftyp")) { invalid = true; continue; }
                        boxSize = ((header[0] & 255L) << 24) | ((header[1] & 255L) << 16)
                                | ((header[2] & 255L) << 8) | (header[3] & 255L);
                        if (boxSize == 1) { headerSize = 16; boxSize = -1; }
                    }
                    if (headerSize == 16 && position == 16) {
                        if ((header[8] & 128) != 0) { invalid = true; continue; }
                        boxSize = 0;
                        for (int j = 8; j < 16; j++) boxSize = (boxSize << 8) | (header[j] & 255L);
                    }
                    if (boxSize >= 0 && position == headerSize) {
                        if (boxSize < headerSize + 8 || boxSize > MediaType.MP4.getMaxBytes()
                                || (boxSize - headerSize - 8) % 4 != 0) invalid = true;
                    }
                    if (boxSize < 0 || position > boxSize || position <= headerSize) continue;
                    long offset = position - headerSize - 1;
                    if (offset >= 4 && offset < 8) continue; // minor version
                    brand[(int) (offset % 4)] = value;
                    if (offset % 4 == 3) {
                        if (isImageBrand(brand, 0) || (offset == 3 && isRejectedMajorBrand(brand, 0))) invalid = true;
                        mp4 |= isMp4Brand(brand, 0);
                    }
                }
            }

            @Override
            public boolean rejected() {
                return invalid || (boxSize >= headerSize + 8 && position >= boxSize && !mp4);
            }

            @Override
            public MediaType result() {
                return !invalid && boxSize >= headerSize + 8 && position >= boxSize && mp4 ? MediaType.MP4 : null;
            }
        };
    }
    @Override
    public MediaType detect(byte[] prefix, int length) {
        if (length >= 3 && (prefix[0] & 255) == 0xff && (prefix[1] & 255) == 0xd8 && (prefix[2] & 255) == 0xff) return MediaType.JPEG;
        if (length >= 8 && (prefix[0] & 255) == 0x89 && matches(prefix, 1, "PNG\r\n\u001a\n")) return MediaType.PNG;
        if (length >= 12 && matches(prefix, 0, "RIFF") && matches(prefix, 8, "WEBP")) return MediaType.WEBP;
        if (length >= 16 && matches(prefix, 4, "ftyp")) {
            long size = ((prefix[0] & 255L) << 24) | ((prefix[1] & 255L) << 16) | ((prefix[2] & 255L) << 8) | (prefix[3] & 255L);
            int headerSize = 8;
            if (size == 1) {
                // 상위 비트가 켜진 unsigned 64-bit 길이는 long 및 판별 범위를 초과합니다.
                if (length < 24 || (prefix[8] & 0x80) != 0) return null;
                size = 0;
                for (int offset = 8; offset < 16; offset++) size = (size << 8) | (prefix[offset] & 255L);
                headerSize = 16;
            }
            int brandsStart = headerSize + 8;
            if (size < brandsStart || size > length || (size - brandsStart) % 4 != 0) return null;
            if (isImageBrand(prefix, headerSize) || isRejectedMajorBrand(prefix, headerSize)) return null;
            boolean mp4 = isMp4Brand(prefix, headerSize);
            for (int offset = brandsStart; offset < size; offset += 4) {
                if (isImageBrand(prefix, offset)) return null;
                mp4 |= isMp4Brand(prefix, offset);
            }
            return mp4 ? MediaType.MP4 : null;
        }
        return null;
    }

    private boolean isImageBrand(byte[] bytes, int offset) {
        return IMAGE_BRANDS.contains(new String(bytes, offset, 4, StandardCharsets.US_ASCII));
    }

    private boolean isRejectedMajorBrand(byte[] bytes, int offset) {
        return matches(bytes, offset, "qt  ") || matches(bytes, offset, "M4A ");
    }

    private boolean isMp4Brand(byte[] bytes, int offset) {
        return MP4_BRANDS.contains(new String(bytes, offset, 4, StandardCharsets.US_ASCII));
    }

    private boolean matches(byte[] bytes, int offset, String expected) {
        byte[] value = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset + value.length > bytes.length) return false;
        for (int i = 0; i < value.length; i++) if (bytes[offset + i] != value[i]) return false;
        return true;
    }
}
