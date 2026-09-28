package repit.repit_api_server.domain.userdata.recording.dto.response;

import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;

import java.time.LocalDateTime;

// S3 주소는 내려주지 않는다. 얼굴과 목소리가 담긴 파일이라 주소가 새면 그대로 열린다.
public record InterviewRecordingResponse(
        Long recordingId,
        Long interviewId,
        RecordingKind kind,
        // 면접 화면 전체 녹화에는 없다.
        Long questionId,
        // 답변 파일에만 있다. 받은 모양 그대로 소문자로 돌려준다. 웹이 보내지 않았으면 비어 있다.
        String endReason,
        String contentType,
        Long fileSize,
        LocalDateTime createdAt
) {
    public static InterviewRecordingResponse from(InterviewRecordingEntity recording) {
        return new InterviewRecordingResponse(
                recording.getRecordingId(),
                recording.getInterviewId(),
                recording.getKind(),
                recording.getChatQuestionId(),
                recording.getEndReason() == null ? null : recording.getEndReason().wireName(),
                recording.getContentType(),
                recording.getFileSize(),
                recording.getCreatedAt()
        );
    }
}
