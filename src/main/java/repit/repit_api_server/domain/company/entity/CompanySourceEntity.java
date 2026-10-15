package repit.repit_api_server.domain.company.entity;

import jakarta.persistence.*;
import lombok.*;
import repit.repit_api_server.domain.company.entity.enums.CompanySourceType;

import java.time.LocalDate;

/** 기업 정보의 출처. 화면·프롬프트에는 나가지 않고 DB에만 둔다. */
@Entity
@Table(name = "company_source")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CompanySourceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id")
    private Long sourceId;

    @Column(nullable = false)
    private Long companyId;

    // 패키지 안에서 fact가 출처를 가리키는 키.
    @Column(nullable = false, length = 40)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanySourceType sourceType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(length = 200)
    private String title;

    @Column(length = 100)
    private String scope;

    private LocalDate publishedAt;

    @Column(nullable = false)
    private LocalDate collectedAt;
}
