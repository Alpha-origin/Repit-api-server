package repit.repit_api_server.domain.metadata.support;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 자료 PDF를 S3에 둔다. 주소 모양은 옛 업로드 경로(MetaService)와 같다: base-url + "/" + 무작위 키.
 *
 * <p>올리고 받는 내용은 파일로 흘려보낸다. PDF를 힙에 통째로 올리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MaterialFileStorage {

    private static final Logger log = LoggerFactory.getLogger(MaterialFileStorage.class);
    private static final int COPY_BUFFER = 64 * 1024;

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Value("${spring.cloud.aws.s3.base-url}")
    private String baseUrl;

    public String upload(Path pdf) {
        String key = UUID.randomUUID() + ".pdf";
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromFile(pdf));
        return baseUrl + "/" + key;
    }

    public enum DownloadStatus { OK, NOT_FOUND, TOO_LARGE }

    /** OK면 file에 받은 내용이 있다. 다 쓴 뒤 MaterialTempFiles.deleteQuietly로 지운다. */
    public record Download(DownloadStatus status, Path file) {
    }

    /**
     * 저장된 PDF를 임시 파일로 받는다. 옛 업로드 경로는 1GB까지 받았으므로, 크기를 먼저 보고 maxBytes를 넘으면 받지 않는다.
     * 크기를 본 뒤 다른 파일로 바뀌었을 수도 있어 받는 동안에도 maxBytes에서 끊는다.
     *
     * <p>이 버킷의 주소가 아니거나 S3에 파일이 없으면 NOT_FOUND다. 연결 끊김·S3 오류처럼 잠깐의 장애는 파일이 없는 것과
     * 다르다. 없다고 답하면 사용자가 멀쩡한 문서를 지우거나 다시 올리므로, 다시 시도하라는 503으로 던진다.
     */
    public Download download(String fileUrl, long maxBytes) {
        String key = keyOf(fileUrl);
        if (key == null) {
            return new Download(DownloadStatus.NOT_FOUND, null);
        }
        Path file = null;
        try {
            Long size = s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build()).contentLength();
            if (size == null || size > maxBytes) {
                return new Download(DownloadStatus.TOO_LARGE, null);
            }
            file = MaterialTempFiles.create();
            try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build())) {
                if (!copyAtMost(in, file, maxBytes)) {
                    // 닫기만 하면 HTTP 클라이언트가 연결을 다시 쓰려고 남은 본문을 끝까지 읽는다. 먼저 끊는다.
                    in.abort();
                    MaterialTempFiles.deleteQuietly(file);
                    return new Download(DownloadStatus.TOO_LARGE, null);
                }
            }
            return new Download(DownloadStatus.OK, file);
        } catch (S3Exception e) {
            MaterialTempFiles.deleteQuietly(file);
            if (isMissing(e)) {
                log.warn("저장된 자료 PDF가 S3에 없습니다. key={}, 원인={}", key, e.getMessage());
                return new Download(DownloadStatus.NOT_FOUND, null);
            }
            throw unavailable(key, e);
        } catch (SdkException | IOException e) {
            MaterialTempFiles.deleteQuietly(file);
            throw unavailable(key, e);
        }
    }

    /** 버킷 목록 권한(s3:ListBucket)이 없으면 S3는 없는 키에도 404 대신 403을 준다. 둘 다 없는 파일로 본다. */
    private static boolean isMissing(S3Exception e) {
        return e instanceof NoSuchKeyException || e.statusCode() == 404 || e.statusCode() == 403;
    }

    private static ExternalApiException unavailable(String key, Exception cause) {
        log.warn("저장된 자료 PDF를 잠시 읽지 못했습니다. key={}, 원인={}", key, cause.getMessage());
        return new ExternalApiException("저장된 자료를 잠시 읽지 못했습니다. 잠시 후 다시 시도해 주세요.",
                HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    /** 저장이 끝나지 않아 쓸 데가 없어진 파일을 지운다. 실패해도 원래 오류를 가리지 않게 로그만 남긴다. */
    public void deleteQuietly(String fileUrl) {
        String key = keyOf(fileUrl);
        if (key == null) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (SdkException e) {
            log.warn("쓰지 않게 된 자료 PDF를 지우지 못했습니다. key={}, 원인={}", key, e.getMessage());
        }
    }

    /** maxBytes까지만 옮긴다. 넘으면 false다. */
    private static boolean copyAtMost(InputStream in, Path target, long maxBytes) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (copied > maxBytes) {
                    return false;
                }
                out.write(buffer, 0, read);
            }
        }
        return true;
    }

    private String keyOf(String fileUrl) {
        String prefix = baseUrl + "/";
        return fileUrl != null && fileUrl.startsWith(prefix) && fileUrl.length() > prefix.length()
                ? fileUrl.substring(prefix.length())
                : null;
    }
}
