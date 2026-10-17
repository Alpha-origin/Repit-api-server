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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;
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

    /** 이 버킷의 주소가 아니거나 읽지 못하면 빈 값이다. */
    public Optional<byte[]> download(String fileUrl) {
        String key = keyOf(fileUrl);
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build()).asByteArray());
        } catch (SdkException e) {
            log.warn("저장된 자료 PDF를 읽지 못했습니다. key={}, 원인={}", key, e.getMessage());
            return Optional.empty();
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
