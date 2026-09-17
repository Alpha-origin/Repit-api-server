package repit.repit_api_server.domain.userdata.recording.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_recording")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterviewRecordingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recording_id")
    private Long recordingId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    // 채팅 서버 질문 번호. 우리 질문 PK가 아니다 — 질문 행은 면접이 끝나야 생기고, 기록이 다시 오면
    // 지웠다가 새로 만들어져 PK가 바뀐다. 이 번호는 한 면접 안에서 변하지 않아 질문과 잇는 기준이 된다.
    // 업로드에서 필수지만, 필수가 되기 전에 올라온 행은 비어 있을 수 있다.
    private Long chatQuestionId;

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(nullable = false)
    private Long fileSize;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
