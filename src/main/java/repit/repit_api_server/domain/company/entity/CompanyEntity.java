package repit.repit_api_server.domain.company.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.entity.enums.LegalSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 면접에서 고를 수 있는 기업. 기동할 때 기업 데이터 패키지에서 가져온다 — {@code CompanyDataImporter} 참고.
 *
 * <p>행은 지우지 않는다. 면접이 company_id로 가리키고 있어, 내릴 기업은 설정(app.company.hidden-codes)으로 HIDDEN만 만든다.
 * 목록·신규 선택에 내보내는 조건은 {@code CompanyVisibility}가 정한다.
 */
@Entity
@Table(name = "company")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CompanyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long companyId;

    // 패키지의 영구 키. 가져올 때 이 값으로 기존 행을 찾는다.
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 30)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String legalName;

    // 검색용 다른 이름.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> aliases;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_company_id")
    private CompanyEntity parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanyCategory category;

    // 비어 있으면 미확인이다.
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private LegalSize legalSize;

    @Column(columnDefinition = "TEXT")
    private String legalSizeSource;

    @Column(length = 8, unique = true)
    private String dartCorpCode;

    @Column(length = 10, unique = true)
    private String businessRegNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CompanyStatus status;

    // 채팅 서버 꼬리질문이 받는 유일한 기업 정보.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String interviewerBrief;

    @Column(nullable = false)
    private LocalDate dataAsOf;

    // 출처가 모두 SAMPLE인 개발용 패키지. 허용한 환경(app.company.allow-sample)에서만 목록·면접 생성에 내보낸다.
    @Column(nullable = false)
    private boolean sample;

    @Column(length = 50)
    private String reviewedBy;

    private LocalDate reviewedAt;

    // 가져온 패키지 파일 바이트의 SHA-256. 같으면 다시 가져오지 않는다.
    @Column(nullable = false, length = 64)
    private String contentHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
