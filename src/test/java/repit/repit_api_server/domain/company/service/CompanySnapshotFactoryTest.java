package repit.repit_api_server.domain.company.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.entity.enums.LegalSize;
import repit.repit_api_server.domain.company.entity.enums.SignalsOrigin;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.global.exception.BusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 면접에 남기는 기업 스냅샷. 분석 서버 계약(00 §6-1)과 같은 모양이어야 그대로 실어 보낼 수 있다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanySnapshotFactoryTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyFactRepository companyFactRepository;

    private CompanySnapshotFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CompanySnapshotFactory(companyRepository, companyFactRepository);

        when(companyRepository.findByCompanyIdAndStatus(3L, CompanyStatus.PUBLISHED)).thenReturn(Optional.of(
                CompanyEntity.builder()
                        .companyId(3L)
                        .code("sample-commerce")
                        .displayName("샘플커머스")
                        .legalName("샘플커머스 주식회사")
                        .category(CompanyCategory.CONSUMER)
                        // 규모가 있어도 스냅샷에는 싣지 않는다.
                        .legalSize(LegalSize.LARGE)
                        .status(CompanyStatus.PUBLISHED)
                        .interviewerBrief("샘플커머스는 가상의 기업이다.")
                        .build()));
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L)).thenReturn(List.of(
                fact(21L, CompanyFactKind.BUSINESS, "샘플마켓", 0, List.of("잘못 들어간 신호")),
                fact(23L, CompanyFactKind.TALENT, "오너십", 1, List.of("끝까지 해결한 경험을 설명함"))));
    }

    private CompanyFactEntity fact(Long factId, CompanyFactKind kind, String title, int order, List<String> signals) {
        return CompanyFactEntity.builder()
                .factId(factId)
                .companyId(3L)
                .kind(kind)
                .title(title)
                .content(title + " 설명")
                .positiveSignals(signals)
                .concernSignals(List.of("우려"))
                .signalsOrigin(SignalsOrigin.DERIVED)
                .sourceId(1L)
                .sortOrder(order)
                .build();
    }

    @Test
    void 정보는_표시_순서대로_id를_문자열로_담는다() {
        CompanySnapshot snapshot = factory.create(3L);

        assertThat(snapshot.companyId()).isEqualTo("3");
        assertThat(snapshot.displayName()).isEqualTo("샘플커머스");
        assertThat(snapshot.category()).isEqualTo("CONSUMER");
        assertThat(snapshot.interviewerBrief()).isEqualTo("샘플커머스는 가상의 기업이다.");
        assertThat(snapshot.facts()).extracting(CompanySnapshot.Fact::factId).containsExactly("21", "23");
        assertThat(snapshot.facts()).extracting(CompanySnapshot.Fact::title).containsExactly("샘플마켓", "오너십");
        assertThat(snapshot.facts()).extracting(CompanySnapshot.Fact::kind).containsExactly("BUSINESS", "TALENT");
    }

    @Test
    void 인재상이_아닌_정보의_신호는_빈_목록이다() {
        CompanySnapshot snapshot = factory.create(3L);

        assertThat(snapshot.facts().getFirst().positiveSignals()).isEmpty();
        assertThat(snapshot.facts().getFirst().concernSignals()).isEmpty();
        assertThat(snapshot.facts().get(1).positiveSignals()).containsExactly("끝까지 해결한 경험을 설명함");
        assertThat(snapshot.facts().get(1).concernSignals()).containsExactly("우려");
    }

    @Test
    void 스냅샷_JSON에는_규모와_출처가_없다() {
        JsonNode json = JsonMapper.shared().valueToTree(factory.create(3L));

        assertThat(json.has("legalSize")).isFalse();
        assertThat(json.get("facts").get(1).has("signalsOrigin")).isFalse();
        assertThat(json.get("facts").get(1).has("sourceId")).isFalse();
        // 채팅 서버용 요약 메서드는 속성이 아니다.
        assertThat(json.has("chat")).isFalse();
        assertThat(json.get("facts").get(0).get("positiveSignals").isArray()).isTrue();
    }

    @Test
    void 공개되지_않았거나_없는_기업이면_404다() {
        when(companyRepository.findByCompanyIdAndStatus(4L, CompanyStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.create(4L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 분석_서버_상한을_넘으면_만들지_않는다() {
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L))
                .thenReturn(List.of(fact(21L, CompanyFactKind.BUSINESS, "가".repeat(21), 0, List.of())));

        assertThatThrownBy(() -> factory.create(3L)).isInstanceOf(IllegalStateException.class);
    }
}
