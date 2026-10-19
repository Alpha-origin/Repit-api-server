package repit.repit_api_server.domain.metadata.support;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import repit.repit_api_server.domain.metadata.support.PdfInspector.Status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class PdfInspectorTest {

    private final PdfInspector inspector = new PdfInspector();

    @TempDir
    private Path dir;

    private Path write(String name, byte[] content) throws Exception {
        return Files.write(dir.resolve(name), content);
    }

    @Test
    void 정상_PDF는_쪽수를_센다() throws Exception {
        assertThat(inspector.inspect(write("ok.pdf", TestPdfs.pdf(3, "ok")))).isEqualTo(new PdfInspector.Result(Status.OK, 3));
    }

    @Test
    void PDF가_아닌_내용과_암호_PDF를_가린다() throws Exception {
        assertThat(inspector.inspect(write("bad.pdf", "not a pdf".getBytes())).status()).isEqualTo(Status.BROKEN);
        assertThat(inspector.inspect(write("locked.pdf", TestPdfs.encryptedPdf())).status()).isEqualTo(Status.ENCRYPTED);
    }

    @Test
    void PDFBox가_런타임_예외를_던져도_500이_아니라_손상으로_본다() throws Exception {
        Path pdf = write("weird.pdf", TestPdfs.pdf(1, "weird"));
        try (MockedStatic<Loader> loader = mockStatic(Loader.class)) {
            loader.when(() -> Loader.loadPDF(any(File.class))).thenThrow(new ClassCastException("COSInteger"));

            assertThat(inspector.inspect(pdf).status()).isEqualTo(Status.BROKEN);
        }
    }

    /** 객체를 끝없이 겹친 PDF는 파서의 재귀를 넘친다. 500이 아니라 손상으로 본다. */
    @Test
    void 파서가_스택을_넘쳐도_손상으로_본다() throws Exception {
        Path pdf = write("nested.pdf", TestPdfs.pdf(1, "nested"));
        try (MockedStatic<Loader> loader = mockStatic(Loader.class)) {
            loader.when(() -> Loader.loadPDF(any(File.class))).thenThrow(new StackOverflowError());

            assertThat(inspector.inspect(pdf).status()).isEqualTo(Status.BROKEN);
        }
    }
}
