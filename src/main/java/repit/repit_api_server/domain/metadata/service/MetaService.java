package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MetaService {
    private final S3Client s3Client;
    private final AuthServerClient authServerClient;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Value("${spring.cloud.aws.s3.base-url}")
    private String baseUrl;


    // GitUrl, 포트폴리오 업로드
    public MetaDataResponse dataUpload(String authorization,
                                       MultipartFile file,
                                       String gitUrl) throws IOException {
        String s3Url = uploadFile(file);
        authServerClient.saveMetaData(
                authorization,
                MetaDataRequest.builder()
                        .gitUrl(gitUrl)
                        .fileUrl(s3Url)
                        .build()
        );

        return MetaDataResponse.builder()
                .gitUrl(gitUrl)
                .fileUrl(s3Url)
                .build();
    }


    // metaData 반환
    public MetaDataResponse getMetaData(String authorization) {
        return authServerClient.getMetaData(authorization);
    }

    // S3 file 업로드
    public String uploadFile(MultipartFile file) throws IOException {
        // 원본 파일명과 확장자 추출
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // 고유한 파일명 생성 (uuid)
        String fileName = UUID.randomUUID().toString() + extension;

        // S3 업로드 요청 생성
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        // S3에 파일 업로드
        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        // 업로드된 파일의 URL 반환
        return baseUrl + "/" + fileName;
    }
}
