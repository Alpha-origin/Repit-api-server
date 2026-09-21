package repit.repit_api_server.domain.userdata.recording.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 올라온 파일이 무엇인지 앞 바이트로 가리는 부분.
 *
 * <p>브라우저마다 녹음·녹화 결과가 다른 컨테이너로 나온다. 하나라도 못 알아보면 그 브라우저를 쓴
 * 사용자의 답변은 통째로 415가 되고, 반대로 아무거나 받으면 열리지 않는 파일이 채점까지 간다.
 */
class RecordingFormatTest {

    @Test
    void 브라우저가_내놓는_컨테이너를_알아본다() {
        assertThat(RecordingFormat.detect(mp4())).isEqualTo(RecordingFormat.MP4);
        assertThat(RecordingFormat.detect(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0))).isEqualTo(RecordingFormat.WEBM);
        assertThat(RecordingFormat.detect(ascii("OggS____"))).isEqualTo(RecordingFormat.OGG);
        assertThat(RecordingFormat.detect(ascii("RIFF????WAVE"))).isEqualTo(RecordingFormat.WAV);
    }

    /** MP3는 ID3 태그로 시작하기도 하고 곧바로 프레임부터 시작하기도 한다. 둘 다 같은 파일이다. */
    @Test
    void MP3는_태그가_있든_없든_알아본다() {
        assertThat(RecordingFormat.detect(bytes('I', 'D', '3', 3, 0, 0, 0, 0))).isEqualTo(RecordingFormat.MP3);
        assertThat(RecordingFormat.detect(bytes(0xFF, 0xFB, 0x90, 0x00))).isEqualTo(RecordingFormat.MP3);
    }

    @Test
    void 아는_형식이_아니면_비운다() {
        assertThat(RecordingFormat.detect(ascii("not a media file"))).isNull();
        assertThat(RecordingFormat.detect(new byte[0])).isNull();
        // ftyp은 5번째 바이트부터다. 앞에서 찾으면 엉뚱한 파일이 MP4가 된다.
        assertThat(RecordingFormat.detect(ascii("ftyp________"))).isNull();
    }

    /** 소리만 담는 형식은 면접 화면 녹화가 될 수 없다. */
    @Test
    void 그림을_담을_수_있는_형식을_가린다() {
        assertThat(RecordingFormat.MP4.canHoldVideo()).isTrue();
        assertThat(RecordingFormat.WEBM.canHoldVideo()).isTrue();
        assertThat(RecordingFormat.MP3.canHoldVideo()).isFalse();
        assertThat(RecordingFormat.WAV.canHoldVideo()).isFalse();
    }

    private static byte[] mp4() {
        return bytes(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm');
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
