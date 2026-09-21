package repit.repit_api_server.domain.userdata.recording.entity.enums;

/**
 * 웹이 올리는 면접 파일의 종류.
 *
 * <p>웹은 두 가지를 따로 올린다. 질문에 답할 때마다 그 답변의 음성을 하나씩, 그리고 면접을 멈출 때
 * 면접 화면을 처음부터 담은 영상을 하나. 앞엣것은 질문에 붙고 뒤엣것은 면접 전체에 붙는다.
 */
public enum RecordingKind {
    /** 질문 하나의 답변 파일. 어느 질문인지가 반드시 붙는다. */
    ANSWER,
    /** 면접 화면 전체 녹화. 질문 하나에 매이지 않아 면접에 하나만 쓴다. */
    FULL_INTERVIEW
}
