package repit.repit_api_server.domain.company.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.SignalsOrigin;

import java.util.List;

/**
 * 기업 정보 한 줄. 패키지를 다시 가져오면 지우고 다시 넣으므로 fact_id가 바뀐다.
 * 이미 만든 면접은 스냅샷 안의 factId를 쓰므로 영향이 없다.
 */
@Entity
@Table(name = "company_fact")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CompanyFactEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fact_id")
    private Long factId;

    @Column(nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanyFactKind kind;

    // 짧은 이름. 목록 카드 키워드와 피드백 태그에 쓴다.
    @Column(nullable = false, length = 20)
    private String title;

    @Column(nullable = false, length = 200)
    private String content;

    // 인재상(TALENT)에만 있다. 나머지는 빈 목록이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> positiveSignals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> concernSignals;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private SignalsOrigin signalsOrigin;

    @Column(nullable = false)
    private Long sourceId;

    // 패키지 파일 안 순서(0부터). 표시·전달 순서다.
    @Column(nullable = false)
    private int sortOrder;
}
