package repit.repit_api_server.domain.metadata.support;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 올린 PDF를 열어 쪽수를 센다. 쪽수는 서버만 센다(비개발 직군 명세 가정 A-8).
 *
 * <p>파일에서 바로 연다. 내용을 힙에 통째로 올리지 않는다.
 * 열 때 암호가 필요한 PDF만 암호화로 본다. 편집 제한만 걸린 PDF는 분석 서버도 읽을 수 있어 받는다.
 */
@Component
public class PdfInspector {

    public enum Status { OK, BROKEN, ENCRYPTED }

    public record Result(Status status, int pageCount) {

        static Result ok(int pageCount) {
            return new Result(Status.OK, pageCount);
        }
    }

    public Result inspect(Path pdf) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int pages = document.getNumberOfPages();
            // 쪽이 하나도 없으면 읽을 내용이 없다.
            return pages > 0 ? Result.ok(pages) : new Result(Status.BROKEN, 0);
        } catch (InvalidPasswordException e) {
            return new Result(Status.ENCRYPTED, 0);
        } catch (IOException | RuntimeException e) {
            // 깨진 PDF는 IOException 말고도 IllegalArgumentException·ClassCastException 같은 런타임 예외로 터진다.
            return new Result(Status.BROKEN, 0);
        } catch (StackOverflowError e) {
            // 객체를 끝없이 겹친 PDF는 파서의 재귀를 넘친다. 스택은 여기까지 풀렸으므로 손상으로 보고 이어 간다.
            return new Result(Status.BROKEN, 0);
        }
    }
}
