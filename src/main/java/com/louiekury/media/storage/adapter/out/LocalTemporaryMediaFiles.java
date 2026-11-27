package com.louiekury.media.storage.adapter.out;

import com.louiekury.media.storage.application.port.out.TemporaryMediaFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalTemporaryMediaFiles implements TemporaryMediaFiles {
    public Path create() throws IOException { return Files.createTempFile("media-", ".tmp"); }
    public OutputStream open(Path path) throws IOException { return Files.newOutputStream(path); }
    public void delete(Path path) throws IOException { Files.deleteIfExists(path); }
}
