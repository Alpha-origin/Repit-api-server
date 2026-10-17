package repit.repit_api_server.domain.metadata.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage.DownloadStatus;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 옛 업로드 경로는 1GB까지 받았으므로, 저장된 PDF를 받기 전에 크기를 먼저 본다. */
@ExtendWith(MockitoExtension.class)
class MaterialFileStorageTest {

    private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com";
    private static final long MAX = 50L * 1024 * 1024;

    @Mock
    private S3Client s3Client;

    private MaterialFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new MaterialFileStorage(s3Client);
        ReflectionTestUtils.setField(storage, "bucketName", "bucket");
        ReflectionTestUtils.setField(storage, "baseUrl", BASE_URL);
    }

    @Test
    void 크기가_한도를_넘으면_내용을_받지_않는다() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(MAX + 1).build());

        MaterialFileStorage.Download download = storage.download(BASE_URL + "/big.pdf", MAX);

        assertThat(download.status()).isEqualTo(DownloadStatus.TOO_LARGE);
        verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void 한도_안이면_내용을_받는다() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(3L).build());
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[]{1, 2, 3}));

        MaterialFileStorage.Download download = storage.download(BASE_URL + "/ok.pdf", MAX);

        assertThat(download.status()).isEqualTo(DownloadStatus.OK);
        assertThat(download.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void 이_버킷_주소가_아니거나_없는_파일이면_NOT_FOUND() {
        assertThat(storage.download("https://other.example.com/a.pdf", MAX).status()).isEqualTo(DownloadStatus.NOT_FOUND);

        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("no").build());
        assertThat(storage.download(BASE_URL + "/gone.pdf", MAX).status()).isEqualTo(DownloadStatus.NOT_FOUND);
    }
}
