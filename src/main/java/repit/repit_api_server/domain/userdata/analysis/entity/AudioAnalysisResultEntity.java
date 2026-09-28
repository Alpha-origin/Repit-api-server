package repit.repit_api_server.domain.userdata.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/** 녹음 하나의 음성 분석 결과. 분석에 실패한 녹음도 행이 남는다. */
@Entity
@Table(name = "audio_analysis_result")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AudioAnalysisResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(nullable = false)
    private Long analysisId;

    // 분석 서버가 요청값을 되돌려주는 값이다. 숫자로 읽지 못하면 비워 둔다.
    private Long recordingId;

    private Long questionId;

    private Long answerId;

    // ready / partial / unavailable을 대문자로. 모르는 값도 버리지 않고 그대로 둔다.
    @Column(length = 20)
    private String status;

    // 디코딩된 음성 길이. 분석 서버가 확보하지 못하면 비어 있다.
    private Long durationMs;

    // 말 속도·침묵·속도 안정성. 분석 서버가 보낸 {status, data, error} 그대로.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> timing;

    // 말 더듬기·간투사·문장 완결성. 분석 서버가 보낸 {status, data, error} 그대로.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> fluency;

    // 실패 사유 전문. 문자열이 아니면 JSON 문자열로 둔다.
    @Column(columnDefinition = "TEXT")
    private String error;

    // 분기에 쓰는 값. message는 코드마다 같은 문구라 쓸 수 없다.
    @Column(length = 100)
    private String errorCode;

    // 참이면 새 요청으로 살릴 수 있는 실패다. 분석 서버가 스스로 다시 시도하는 중이라는 뜻은 아니다.
    private Boolean errorRetryable;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
