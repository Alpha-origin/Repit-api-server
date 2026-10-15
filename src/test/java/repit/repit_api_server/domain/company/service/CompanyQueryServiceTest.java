package repit.repit_api_server.domain.company.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.company.dto.CompanySummaryResponse;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 공개 기업 목록·검색(04 §5). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyQueryServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyFactRepository companyFactRepository;

    private CompanyQueryService service;

    @BeforeEach
    void setUp() {
        service = new CompanyQueryService(companyRepository, companyFactRepository);

        CompanyEntity commerce = company(3L, "샘플커머스", CompanyCategory.CONSUMER, List.of("샘플마켓", "Sample Commerce"));
        CompanyEntity games = company(4L, "샘플게임즈", CompanyCategory.GAME, List.of("Sample Games"));
        CompanyEntity pay = company(5L, "샘플페이", CompanyCategory.FINTECH, List.of("Sample Pay"));
        CompanyEntity otherPay = company(6L, "가나페이", CompanyCategory.FINTECH, List.of());
        otherPay.setParent(pay);
        when(companyRepository.findWithParentByStatus(CompanyStatus.PUBLISHED))
                .thenReturn(new ArrayList<>(List.of(commerce, games, pay, otherPay)));

        when(companyFactRepository.findAllByKindOrderBySortOrder(CompanyFactKind.TALENT)).thenReturn(List.of(
                talent(3L, "오너십"), talent(3L, "고객 관점"), talent(3L, "집요함"), talent(3L, "네 번째"),
                talent(5L, "정확성")));
    }

    private CompanyEntity company(Long id, String displayName, CompanyCategory category, List<String> aliases) {
        return CompanyEntity.builder()
                .companyId(id)
                .code("code-" + id)
                .displayName(displayName)
                .legalName(displayName + " 주식회사")
                .aliases(aliases)
                .category(category)
                .status(CompanyStatus.PUBLISHED)
                .dataAsOf(LocalDate.parse("2026-10-15"))
                .build();
    }

    private CompanyFactEntity talent(Long companyId, String title) {
        return CompanyFactEntity.builder().companyId(companyId).kind(CompanyFactKind.TALENT).title(title).build();
    }

    private List<String> names(List<CompanySummaryResponse> companies) {
        return companies.stream().map(CompanySummaryResponse::displayName).toList();
    }

    @Test
    void 분류_순서_다음_이름_가나다순으로_정렬한다() {
        assertThat(names(service.search(null, null))).containsExactly("가나페이", "샘플페이", "샘플게임즈", "샘플커머스");
    }

    @Test
    void 별칭으로도_찾는다() {
        assertThat(names(service.search("샘플마켓", null))).containsExactly("샘플커머스");
    }

    @Test
    void 공백과_대소문자는_보지_않는다() {
        assertThat(names(service.search(" sample  COMMERCE ", null))).containsExactly("샘플커머스");
        assertThat(names(service.search("샘플 커머스", null))).containsExactly("샘플커머스");
        // 법인 이름도 찾는다.
        assertThat(names(service.search("게임즈주식", null))).containsExactly("샘플게임즈");
    }

    @Test
    void 분류로_거른다() {
        assertThat(names(service.search(null, CompanyCategory.FINTECH))).containsExactly("가나페이", "샘플페이");
        assertThat(names(service.search("샘플", CompanyCategory.FINTECH))).containsExactly("샘플페이");
    }

    @Test
    void 인재상_키워드는_title을_순서대로_세_개까지_담는다() {
        CompanySummaryResponse commerce = service.search("샘플커머스", null).getFirst();

        assertThat(commerce.talentKeywords()).containsExactly("오너십", "고객 관점", "집요함");
        assertThat(service.search("샘플게임즈", null).getFirst().talentKeywords()).isEmpty();
    }

    @Test
    void 확인하지_못한_규모는_null_그대로_내보낸다() {
        CompanySummaryResponse commerce = service.search("샘플커머스", null).getFirst();

        assertThat(commerce.legalSize()).isNull();
        assertThat(commerce.legalName()).isEqualTo("샘플커머스 주식회사");
        assertThat(commerce.parentDisplayName()).isNull();
        assertThat(commerce.dataAsOf()).isEqualTo(LocalDate.parse("2026-10-15"));
        assertThat(service.search("가나페이", null).getFirst().parentDisplayName()).isEqualTo("샘플페이");
    }

    @Test
    void 공개_기업이_없으면_빈_목록이다() {
        when(companyRepository.findWithParentByStatus(CompanyStatus.PUBLISHED)).thenReturn(List.of());

        assertThat(service.search(null, null)).isEmpty();
    }

    @Test
    void 검색어가_너무_짧거나_길면_422다() {
        for (String q : List.of("샘", "가".repeat(31))) {
            assertThatThrownBy(() -> service.search(q, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        // 빈 검색어는 검색어가 없는 것과 같다.
        assertThat(service.search("  ", null)).hasSize(4);
    }

    /** 다른 곳에서 붙여 넣은 검색어에는 NBSP(U+00A0)·전각 공백(U+3000)이 섞여 온다. */
    @Test
    void 유니코드_공백도_보지_않는다() {
        assertThat(names(service.search("샘플\u00A0커머스", null))).containsExactly("샘플커머스");
        assertThat(names(service.search("샘플\u3000커머스", null))).containsExactly("샘플커머스");
    }

    @Test
    void 숨긴_모회사의_이름은_내보내지_않는다() {
        CompanyEntity hiddenParent = company(9L, "숨긴모회사", CompanyCategory.FINTECH, List.of());
        hiddenParent.setStatus(CompanyStatus.HIDDEN);
        CompanyEntity child = company(10L, "자회사페이", CompanyCategory.FINTECH, List.of());
        child.setParent(hiddenParent);
        when(companyRepository.findWithParentByStatus(CompanyStatus.PUBLISHED)).thenReturn(List.of(child));

        assertThat(service.search(null, null).getFirst().parentDisplayName()).isNull();
    }
}
