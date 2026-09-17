package repit.repit_api_server.domain.userdata.recording.dto.response;

import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;

import java.time.LocalDateTime;

// S3 주소는 내려주지 않는다. 얼굴과 목소리가 담긴 영상이라 주소가 새면 그대로 열린다.
public record InterviewRecordingResponse(
        Long recordingId,
        Long interviewId,
        Long questionId,
        Long fileSize,
        LocalDateTime createdAt
) {
    public static InterviewRecordingResponse from(InterviewRecordingEntity recording) {
        return new InterviewRecordingResponse(
                recording.getRecordingId(),
                recording.getInterviewId(),
                recording.getChatQuestionId(),
                recording.getFileSize(),
                recording.getCreatedAt()
        );
    }
}
