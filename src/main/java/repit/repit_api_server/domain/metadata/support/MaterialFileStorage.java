package repit.repit_api_server.domain.metadata.support;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

/**
 * 자료 PDF를 S3에 둔다. 주소 모양은 옛 업로드 경로(MetaService)와 같다: base-url + "/" + 무작위 키.
 */
@Component
@RequiredArgsConstructor
public class MaterialFileStorage {

    private static final Logger log = LoggerFactory.getLogger(MaterialFileStorage.class);

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Value("${spring.cloud.aws.s3.base-url}")
    private String baseUrl;

    public String upload(byte[] pdf) {
        String key = UUID.randomUUID() + ".pdf";
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes(pdf));
        return baseUrl + "/" + key;
    }

    public enum DownloadStatus { OK, NOT_FOUND, TOO_LARGE }

    public record Download(DownloadStatus status, byte[] bytes) {
    }

    /**
     * 저장된 PDF를 받는다. 옛 업로드 경로는 1GB까지 받았으므로, 크기를 먼저 보고 maxBytes를 넘으면 받지 않는다.
     * 이 버킷의 주소가 아니거나 읽지 못하면 NOT_FOUND다.
     */
    public Download download(String fileUrl, long maxBytes) {
        String key = keyOf(fileUrl);
        if (key == null) {
            return new Download(DownloadStatus.NOT_FOUND, null);
        }
        try {
            Long size = s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build()).contentLength();
            if (size == null || size > maxBytes) {
                return new Download(DownloadStatus.TOO_LARGE, null);
            }
            byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()).asByteArray();
            // 크기를 본 뒤 다른 파일로 바뀌었을 수도 있어 받은 크기로 한 번 더 본다.
            return bytes.length > maxBytes
                    ? new Download(DownloadStatus.TOO_LARGE, null)
                    : new Download(DownloadStatus.OK, bytes);
        } catch (SdkException e) {
            log.warn("저장된 자료 PDF를 읽지 못했습니다. key={}, 원인={}", key, e.getMessage());
            return new Download(DownloadStatus.NOT_FOUND, null);
        }
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

    private String keyOf(String fileUrl) {
        String prefix = baseUrl + "/";
        return fileUrl != null && fileUrl.startsWith(prefix) && fileUrl.length() > prefix.length()
                ? fileUrl.substring(prefix.length())
                : null;
    }
}
