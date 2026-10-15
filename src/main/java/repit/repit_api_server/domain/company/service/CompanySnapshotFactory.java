package repit.repit_api_server.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.CompanyLimits;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static repit.repit_api_server.domain.company.CompanyLimits.length;

/** 면접을 만들 때 공개 기업을 스냅샷으로 복사한다. 이후 면접의 모든 경로는 이 스냅샷만 읽는다. */
@Component
@RequiredArgsConstructor
public class CompanySnapshotFactory {

    private final CompanyRepository companyRepository;
    private final CompanyFactRepository companyFactRepository;

    // 기업 행과 fact를 두 문장으로 읽는다. 그 사이 가져오기가 커밋되면 이전 요약과 새 fact가 섞인 스냅샷이 면접에 영구히
    // 남는다. REPEATABLE READ면 두 문장이 같은 시점을 본다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompanySnapshot create(Long companyId) {
        CompanyEntity company = companyRepository.findByCompanyIdAndStatus(companyId, CompanyStatus.PUBLISHED)
                .orElseThrow(() -> BusinessException.notFound("선택한 기업을 찾을 수 없습니다."));

        List<CompanySnapshot.Fact> facts = companyFactRepository.findAllByCompanyIdOrderBySortOrder(companyId).stream()
                .map(this::toFact)
                .toList();
        CompanySnapshot snapshot = new CompanySnapshot(String.valueOf(company.getCompanyId()),
                company.getDisplayName(), company.getCategory().name(), facts, company.getInterviewerBrief());
        verifyLimits(snapshot);
        return snapshot;
    }

    private CompanySnapshot.Fact toFact(CompanyFactEntity fact) {
        boolean talent = fact.getKind() == CompanyFactKind.TALENT;
        return new CompanySnapshot.Fact(String.valueOf(fact.getFactId()), fact.getKind().name(),
                fact.getTitle(), fact.getContent(),
                talent ? fact.getPositiveSignals() : List.of(),
                talent ? fact.getConcernSignals() : List.of());
    }

    /**
     * 분석 서버 계약(00 §6-1). 어기면 분석 서버가 재작성·피드백 요청을 422로 거부한다. 스냅샷은 면접에 고정되므로,
     * 어긴 채 만든 면접은 다시 시도해도 같은 422라 영영 시작하지 못한다. 그래서 면접을 만들기 전에 막는다.
     * 가져오기 검증을 통과한 데이터라면 생기지 않으므로, 생겼다면 데이터를 고칠 일이다.
     */
    private void verifyLimits(CompanySnapshot snapshot) {
        Set<String> kinds = snapshot.facts().stream().map(CompanySnapshot.Fact::kind).collect(Collectors.toSet());
        boolean within = within(snapshot.displayName(), CompanyLimits.DISPLAY_NAME)
                && within(snapshot.interviewerBrief(), CompanyLimits.INTERVIEWER_BRIEF)
                && !snapshot.facts().isEmpty()
                && snapshot.facts().size() <= CompanyLimits.FACTS
                && kinds.contains(CompanyFactKind.TALENT.name())
                && kinds.contains(CompanyFactKind.BUSINESS.name())
                && snapshot.facts().stream().allMatch(fact ->
                        within(fact.title(), CompanyLimits.FACT_TITLE)
                                && within(fact.content(), CompanyLimits.FACT_CONTENT)
                                && withinSignals(fact.positiveSignals())
                                && withinSignals(fact.concernSignals()));
        if (!within) {
            throw new IllegalStateException("기업 정보가 분석 서버 계약에 맞지 않습니다. companyId=" + snapshot.companyId());
        }
    }

    private boolean withinSignals(List<String> signals) {
        return signals.size() <= CompanyLimits.SIGNALS_PER_FACT
                && signals.stream().allMatch(signal -> within(signal, CompanyLimits.SIGNAL));
    }

    /** 1자 이상, 상한 이하. 분석 서버는 빈 문자열도 거부한다. */
    private static boolean within(String value, int max) {
        return value != null && !value.isBlank() && length(value) <= max;
    }
}
