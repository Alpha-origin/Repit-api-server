package repit.repit_api_server.domain.metadata.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** 테스트용 PDF. 같은 쪽수라도 title이 다르면 내용(해시)이 달라 중복으로 보지 않는다. */
public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] pdf(int pages, String title) {
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle(title);
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 열 때 암호가 필요한 PDF. */
    public static byte[] encryptedPdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
