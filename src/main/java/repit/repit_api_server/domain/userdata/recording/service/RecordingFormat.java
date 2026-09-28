package repit.repit_api_server.domain.userdata.recording.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 올라온 파일이 실제로 무엇인지 앞 바이트로 가린다.
 *
 * <p>파일 이름의 확장자나 요청에 붙은 Content-Type은 보내는 쪽이 마음대로 적는 값이다. 그대로 믿으면
 * 소리도 그림도 없는 파일이 분석 서버까지 넘어가서 거기서야 깨진다.
 */
enum RecordingFormat {

    // 첫 박스가 ftyp다. 앞 4바이트는 박스 크기라 그 뒤를 본다. MP4와 M4A가 같은 컨테이너다.
    MP4("mp4", "video/mp4", "audio/mp4"),
    // EBML 머리. WebM과 MKV가 같다.
    WEBM("webm", "video/webm", "audio/webm"),
    OGG("ogg", "video/ogg", "audio/ogg"),
    MP3("mp3", null, "audio/mpeg"),
    WAV("wav", null, "audio/wav"),
    FLAC("flac", null, "audio/flac"),
    // 컨테이너 없이 프레임만 잇는 AAC. 사파리의 MediaRecorder가 이 형식으로 녹음한다.
    AAC("aac", null, "audio/aac");

    private final String extension;
    private final String videoContentType;
    private final String audioContentType;

    RecordingFormat(String extension, String videoContentType, String audioContentType) {
        this.extension = extension;
        this.videoContentType = videoContentType;
        this.audioContentType = audioContentType;
    }

    String extension() {
        return extension;
    }

    /** 그림이 담길 수 있는 컨테이너인지. 소리만 담는 형식으로 면접 화면 녹화를 받을 수는 없다. */
    boolean canHoldVideo() {
        return videoContentType != null;
    }

    String videoContentType() {
        return videoContentType;
    }

    String audioContentType() {
        return audioContentType;
    }

    /** 앞 12바이트로 가린다. 어느 것도 아니면 null. */
    static RecordingFormat detect(byte[] header) {
        if (startsWith(header, 0, "OggS")) {
            return OGG;
        }
        if (startsWith(header, 0, "RIFF") && startsWith(header, 8, "WAVE")) {
            return WAV;
        }
        if (startsWith(header, 4, "ftyp")) {
            return MP4;
        }
        if (header.length >= 4 && header[0] == (byte) 0x1A && header[1] == (byte) 0x45
                && header[2] == (byte) 0xDF && header[3] == (byte) 0xA3) {
            return WEBM;
        }
        if (startsWith(header, 0, "fLaC")) {
            return FLAC;
        }
        if (startsWith(header, 0, "ID3")) {
            return MP3;
        }
        // 컨테이너 없이 프레임부터 시작하는 MP3와 AAC(ADTS). 둘 다 11비트가 모두 1인 싱크 워드로 시작해서
        // 그것만으로는 갈리지 않는다. 이어지는 2비트가 계층이고, MPEG에 계층 0은 없어 그 값이면 AAC다.
        if (header.length >= 2 && header[0] == (byte) 0xFF && (header[1] & 0xE0) == 0xE0) {
            return (header[1] & 0x06) == 0 ? AAC : MP3;
        }
        return null;
    }

    private static boolean startsWith(byte[] header, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
        return header.length >= offset + expected.length
                && Arrays.equals(header, offset, offset + expected.length, expected, 0, expected.length);
    }
}
