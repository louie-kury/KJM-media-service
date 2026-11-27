package com.louiekury.media.validation.application.port.out;

import com.louiekury.media.validation.domain.MediaType;

public interface MediaContentDetector {
    MediaType detect(byte[] prefix, int length);

    Mp4Scanner mp4Scanner();

    interface Mp4Scanner {
        void accept(byte[] bytes, int length);
        boolean rejected();
        MediaType result();
    }
}
