package repit.repit_api_server.domain.metadata.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 자료 PDF를 검사·업로드하는 동안 잠깐 두는 임시 파일. 힙에 PDF를 통째로 올리지 않으려고 쓴다. */
public final class MaterialTempFiles {

    private static final Logger log = LoggerFactory.getLogger(MaterialTempFiles.class);

    private MaterialTempFiles() {
    }

    public static Path create() {
        try {
            return Files.createTempFile("repit-material-", ".pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("자료 임시 파일을 만들지 못했습니다.", e);
        }
    }

    public static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("자료 임시 파일을 지우지 못했습니다. path={}, 원인={}", file, e.getMessage());
        }
    }
}
