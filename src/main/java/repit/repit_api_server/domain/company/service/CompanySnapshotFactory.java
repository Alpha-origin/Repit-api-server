package repit.repit_api_server.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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

import static repit.repit_api_server.domain.company.CompanyLimits.length;

/** 면접을 만들 때 공개 기업을 스냅샷으로 복사한다. 이후 면접의 모든 경로는 이 스냅샷만 읽는다. */
@Component
@RequiredArgsConstructor
public class CompanySnapshotFactory {

    private final CompanyRepository companyRepository;
    private final CompanyFactRepository companyFactRepository;

    @Transactional(readOnly = true)
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
     * 분석 서버 계약의 상한. 넘으면 분석 서버가 422로 거부해 면접 준비가 그 자리에서 막힌다.
     * 가져오기 검증을 통과한 데이터라면 생기지 않으므로, 생겼다면 데이터를 고칠 일이다.
     */
    private void verifyLimits(CompanySnapshot snapshot) {
        boolean within = length(snapshot.displayName()) <= CompanyLimits.DISPLAY_NAME
                && !snapshot.facts().isEmpty()
                && snapshot.facts().size() <= CompanyLimits.FACTS
                && length(snapshot.interviewerBrief()) <= CompanyLimits.INTERVIEWER_BRIEF
                && snapshot.facts().stream().allMatch(fact ->
                        length(fact.title()) <= CompanyLimits.FACT_TITLE
                                && length(fact.content()) <= CompanyLimits.FACT_CONTENT
                                && withinSignals(fact.positiveSignals())
                                && withinSignals(fact.concernSignals()));
        if (!within) {
            throw new IllegalStateException("기업 정보가 분석 서버 상한을 넘습니다. companyId=" + snapshot.companyId());
        }
    }

    private boolean withinSignals(List<String> signals) {
        return signals.size() <= CompanyLimits.SIGNALS_PER_FACT
                && signals.stream().allMatch(signal -> length(signal) <= CompanyLimits.SIGNAL);
    }
}
