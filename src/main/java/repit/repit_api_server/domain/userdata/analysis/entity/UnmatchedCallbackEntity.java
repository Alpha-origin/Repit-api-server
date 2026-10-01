package repit.repit_api_server.domain.userdata.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 정상 결과로 반영하지 않은 콜백의 원문. 운영자가 원인을 확인하는 복구 대상이다. */
@Entity
@Table(name = "unmatched_callback")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UnmatchedCallbackEntity {

    public enum Reason {
        /** JSON이 아니거나 필수 필드·상태값·스키마 버전이 맞지 않는다. */
        INVALID_BODY,
        /** 이 서버가 보낸 적 없는 요청이다. */
        UNKNOWN_REQUEST,
        /** 요청은 찾았지만 세션·면접·사용자·영상·작업 id가 다르다. */
        ID_MISMATCH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "callback_id")
    private Long callbackId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Reason reason;

    private String requestId;

    private String jobId;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;
}
