package com.louiekury.media.storage.application.port.out;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public interface TemporaryMediaFiles {
    Path create() throws IOException;
    OutputStream open(Path path) throws IOException;
    void delete(Path path) throws IOException;
}
