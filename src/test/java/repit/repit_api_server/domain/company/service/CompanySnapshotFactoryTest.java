package repit.repit_api_server.domain.company.service;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import repit.repit_api_server.global.error.ErrorCodes;
import repit.repit_api_server.global.exception.BusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
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
        factory = new CompanySnapshotFactory(companyRepository, companyFactRepository, new CompanyVisibility(false));

        when(companyRepository.findForShareByCompanyId(3L)).thenReturn(Optional.of(
                CompanyEntity.builder()
                        .companyId(3L)
                        .code("sample-commerce")
                        .displayName("샘플커머스")
                        .legalName("샘플커머스 주식회사")
                        .category(CompanyCategory.CONSUMER)
                        // 규모가 있어도 스냅샷에는 싣지 않는다.
                        .legalSize(LegalSize.LARGE)
                        .status(CompanyStatus.PUBLISHED)
                        .reviewedBy("검수자")
                        .reviewedAt(LocalDate.parse("2026-10-16"))
                        // 앞뒤 공백은 빼고 싣는다.
                        .interviewerBrief("  샘플커머스는 가상의 기업이다.\n")
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

    private static void assertError(ThrowingCallable call, HttpStatus status,
                                    String code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(status);
                    assertThat(((BusinessException) e).getCode()).isEqualTo(code);
                });
    }

    private static void assertContextUnavailable(ThrowingCallable call) {
        assertError(call, HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.COMPANY_CONTEXT_UNAVAILABLE);
    }

    @Test
    void 공개되지_않았거나_없는_기업이면_404다() {
        when(companyRepository.findForShareByCompanyId(4L)).thenReturn(Optional.empty());
        assertError(() -> factory.create(4L), HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);

        when(companyRepository.findForShareByCompanyId(5L)).thenReturn(Optional.of(CompanyEntity.builder()
                .companyId(5L).status(CompanyStatus.HIDDEN).reviewedBy("검수자").reviewedAt(LocalDate.now()).build()));
        assertError(() -> factory.create(5L), HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);
    }

    /** 공개 상태여도 검수 기록이 없는 실제 기업, 허용하지 않은 환경의 SAMPLE은 고를 수 없다. */
    @Test
    void 검수_조건을_만족하지_않으면_404다() {
        when(companyRepository.findForShareByCompanyId(6L)).thenReturn(Optional.of(CompanyEntity.builder()
                .companyId(6L).status(CompanyStatus.PUBLISHED).build()));
        assertError(() -> factory.create(6L), HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);

        when(companyRepository.findForShareByCompanyId(7L)).thenReturn(Optional.of(CompanyEntity.builder()
                .companyId(7L).status(CompanyStatus.PUBLISHED).sample(true).build()));
        assertError(() -> factory.create(7L), HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);
    }

    @Test
    void 분석_서버_상한을_넘으면_만들지_않는다() {
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L))
                .thenReturn(List.of(fact(21L, CompanyFactKind.BUSINESS, "가".repeat(21), 0, List.of())));

        assertContextUnavailable(() -> factory.create(3L));
    }

    /** 길이는 앞뒤 공백을 뺀 뒤 code point로 센다. 이모지 하나는 한 글자다. */
    @Test
    void 길이는_앞뒤_공백을_빼고_code_point로_센다() {
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L)).thenReturn(List.of(
                fact(21L, CompanyFactKind.BUSINESS, "  " + "😀".repeat(20) + "  ", 0, List.of()),
                fact(23L, CompanyFactKind.TALENT, "오너십", 1, List.of("신".repeat(100)))));

        CompanySnapshot snapshot = factory.create(3L);
        assertThat(snapshot.facts().getFirst().title()).isEqualTo("😀".repeat(20));

        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L)).thenReturn(List.of(
                fact(21L, CompanyFactKind.BUSINESS, "샘플마켓", 0, List.of()),
                fact(23L, CompanyFactKind.TALENT, "오너십", 1, List.of("신".repeat(101)))));
        assertContextUnavailable(() -> factory.create(3L));
    }

    @Test
    void 신호가_네_개면_만들지_않는다() {
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L)).thenReturn(List.of(
                fact(21L, CompanyFactKind.BUSINESS, "샘플마켓", 0, List.of()),
                fact(23L, CompanyFactKind.TALENT, "오너십", 1, List.of("하나", "둘", "셋", "넷"))));

        assertContextUnavailable(() -> factory.create(3L));
    }

    @Test
    void 정보가_스무_개를_넘으면_만들지_않는다() {
        List<CompanyFactEntity> facts = new ArrayList<>();
        facts.add(fact(1L, CompanyFactKind.TALENT, "오너십", 0, List.of()));
        for (long id = 2; id <= 21; id++) {
            facts.add(fact(id, CompanyFactKind.BUSINESS, "사업" + id, (int) id, List.of()));
        }
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L)).thenReturn(facts);

        assertContextUnavailable(() -> factory.create(3L));
    }

    /** 분석 서버는 인재상·주력 사업이 하나도 없는 기업 정보를 422로 거부한다. 고정된 스냅샷이라 면접을 영영 못 연다. */
    @Test
    void 인재상이나_주력_사업이_없으면_만들지_않는다() {
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L))
                .thenReturn(List.of(fact(21L, CompanyFactKind.BUSINESS, "샘플마켓", 0, List.of())));
        assertContextUnavailable(() -> factory.create(3L));

        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(3L))
                .thenReturn(List.of(fact(23L, CompanyFactKind.TALENT, "오너십", 0, List.of())));
        assertContextUnavailable(() -> factory.create(3L));
    }

    @Test
    void 이름이나_요약이_비어_있으면_만들지_않는다() {
        when(companyRepository.findForShareByCompanyId(4L)).thenReturn(Optional.of(
                CompanyEntity.builder().companyId(4L).code("blank").displayName("샘플")
                        .category(CompanyCategory.CONSUMER).status(CompanyStatus.PUBLISHED)
                        .reviewedBy("검수자").reviewedAt(LocalDate.parse("2026-10-16")).interviewerBrief(" ").build()));
        when(companyFactRepository.findAllByCompanyIdOrderBySortOrder(4L)).thenReturn(List.of(
                fact(21L, CompanyFactKind.BUSINESS, "샘플마켓", 0, List.of()),
                fact(23L, CompanyFactKind.TALENT, "오너십", 1, List.of())));

        assertContextUnavailable(() -> factory.create(4L));
    }
}
