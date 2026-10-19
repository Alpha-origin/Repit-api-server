package repit.repit_api_server.domain.metadata.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage.DownloadStatus;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 옛 업로드 경로는 1GB까지 받았으므로, 저장된 PDF는 크기를 먼저 보고 받는 동안에도 한도에서 끊는다. */
@ExtendWith(MockitoExtension.class)
class MaterialFileStorageTest {

    private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com";
    private static final long MAX = 10;

    @Mock
    private S3Client s3Client;

    private MaterialFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new MaterialFileStorage(s3Client);
        ReflectionTestUtils.setField(storage, "bucketName", "bucket");
        ReflectionTestUtils.setField(storage, "baseUrl", BASE_URL);
    }

    private static ResponseInputStream<GetObjectResponse> body(byte[] content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    @Test
    void 크기가_한도를_넘으면_내용을_받지_않는다() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(MAX + 1).build());

        assertThat(storage.download(BASE_URL + "/big.pdf", MAX).status()).isEqualTo(DownloadStatus.TOO_LARGE);
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void 한도_안이면_임시_파일로_받는다() throws Exception {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).build());
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(body(new byte[]{1, 2, 3}));

        MaterialFileStorage.Download download = storage.download(BASE_URL + "/ok.pdf", MAX);

        assertThat(download.status()).isEqualTo(DownloadStatus.OK);
        assertThat(Files.readAllBytes(download.file())).containsExactly(1, 2, 3);
        MaterialTempFiles.deleteQuietly(download.file());
    }

    @Test
    void 크기를_본_뒤_내용이_커졌으면_받는_도중에_연결을_끊고_임시_파일을_지운다() {
        java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).build());
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[(int) MAX + 5]), () -> aborted.set(true))));

        MaterialFileStorage.Download download = storage.download(BASE_URL + "/grew.pdf", MAX);

        assertThat(download.status()).isEqualTo(DownloadStatus.TOO_LARGE);
        assertThat(download.file()).isNull();
        // 닫기 전에 끊어야 HTTP 클라이언트가 남은 본문을 끝까지 읽지 않는다.
        assertThat(aborted).isTrue();
    }

    @Test
    void 이_버킷_주소가_아니거나_없는_파일이면_NOT_FOUND() {
        assertThat(storage.download("https://other.example.com/a.pdf", MAX).status()).isEqualTo(DownloadStatus.NOT_FOUND);

        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("no").build());
        assertThat(storage.download(BASE_URL + "/gone.pdf", MAX).status()).isEqualTo(DownloadStatus.NOT_FOUND);
    }

    /** 버킷 목록 권한이 없으면 S3는 없는 키에도 403을 준다. */
    @Test
    void S3가_없는_키에_403을_줘도_NOT_FOUND() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("Access Denied").build());

        assertThat(storage.download(BASE_URL + "/gone.pdf", MAX).status()).isEqualTo(DownloadStatus.NOT_FOUND);
    }

    /** 잠깐의 장애를 없는 파일로 답하면 사용자가 멀쩡한 문서를 지우거나 다시 올린다. */
    @Test
    void 연결이_끊기거나_S3가_5xx를_주면_없는_파일이_아니라_다시_시도하라는_503() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"))
                .thenThrow(S3Exception.builder().statusCode(503).message("Slow Down").build());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> storage.download(BASE_URL + "/ok.pdf", MAX))
                    .isInstanceOf(ExternalApiException.class)
                    .extracting("statusCode").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void 받는_도중에_끊기면_만든_임시_파일을_지우고_503() throws Exception {
        long before = countMaterialTempFiles();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).build());
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("Connection reset"));

        assertThatThrownBy(() -> storage.download(BASE_URL + "/ok.pdf", MAX))
                .isInstanceOf(ExternalApiException.class)
                .extracting("statusCode").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(countMaterialTempFiles()).isEqualTo(before);
    }

    private static long countMaterialTempFiles() throws Exception {
        try (var stream = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return stream.filter(path -> path.getFileName().toString().startsWith("repit-material-")).count();
        }
    }

    @Test
    void 올릴_때는_파일에서_흘려보내고_버킷_주소를_돌려준다() throws Exception {
        Path pdf = Files.write(Files.createTempFile("upload-", ".pdf"), TestPdfs.pdf(1, "u"));

        String url = storage.upload(pdf);

        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), body.capture());
        assertThat(url).startsWith(BASE_URL + "/").endsWith(".pdf");
        assertThat(body.getValue().optionalContentLength()).contains(Files.size(pdf));
        Files.delete(pdf);
    }
}
