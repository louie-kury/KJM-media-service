package com.louiekury.media.storage.application.port.out;

import java.io.InputStream;

public interface MediaObjectReader {
    /** 호출자는 읽기가 끝나거나 중단되면 반환된 스트림을 닫아야 합니다. */
    InputStream get(String key);
}
