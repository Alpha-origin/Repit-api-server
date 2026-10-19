package repit.repit_api_server.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.CompanyLimits;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.global.error.ErrorCodes;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static repit.repit_api_server.domain.company.CompanyLimits.length;

/**
 * 면접을 만들 때 공개 기업을 스냅샷으로 복사한다. 이후 면접의 모든 경로는 이 스냅샷만 읽는다.
 *
 * <p>면접 생성 트랜잭션에 합류한다. 기업 행을 FOR SHARE로 잡고 fact를 읽으므로, 같은 기업을 갱신하는 가져오기(FOR UPDATE)와
 * 겹치면 한쪽이 끝날 때까지 기다린다. 면접 생성 트랜잭션은 외부 서버를 부르지 않아 잠금을 오래 쥐지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CompanySnapshotFactory {

    private static final Logger log = LoggerFactory.getLogger(CompanySnapshotFactory.class);

    static final String UNAVAILABLE_MESSAGE = "선택한 기업을 찾을 수 없습니다. 기업을 다시 골라주세요.";
    static final String CONTEXT_UNAVAILABLE_MESSAGE =
            "선택한 기업의 면접 정보를 지금 준비할 수 없습니다. 다른 기업을 고르거나 기업 없이 시작해주세요.";
    private static final int FACT_ID_MAX = 32;

    private final CompanyRepository companyRepository;
    private final CompanyFactRepository companyFactRepository;
    private final CompanyVisibility companyVisibility;

    @Transactional
    public CompanySnapshot create(Long companyId) {
        CompanyEntity company = companyRepository.findForShareByCompanyId(companyId)
                .filter(companyVisibility::isSelectable)
                .orElseThrow(() -> new BusinessException(UNAVAILABLE_MESSAGE, HttpStatus.NOT_FOUND,
                        ErrorCodes.COMPANY_UNAVAILABLE));

        List<CompanySnapshot.Fact> facts = companyFactRepository.findAllByCompanyIdOrderBySortOrder(companyId).stream()
                .map(CompanySnapshotFactory::toFact)
                .toList();
        CompanySnapshot snapshot = new CompanySnapshot(String.valueOf(company.getCompanyId()),
                strip(company.getDisplayName()), company.getCategory() == null ? null : company.getCategory().name(),
                facts, strip(company.getInterviewerBrief()));

        List<String> violations = violations(snapshot);
        if (!violations.isEmpty()) {
            // 데이터 원문은 남기지 않는다. 어느 규칙인지만 남겨 데이터를 고친다.
            log.error("공개 기업의 실행 스냅샷을 만들 수 없어 면접 생성을 막습니다. companyId={}, 사유={}",
                    companyId, violations);
            throw new BusinessException(CONTEXT_UNAVAILABLE_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.COMPANY_CONTEXT_UNAVAILABLE);
        }
        return snapshot;
    }

    // 신호는 TALENT에만 싣는다. kind·title·content·id는 저장된 값 그대로이고 비었다고 채우지 않는다.
    private static CompanySnapshot.Fact toFact(CompanyFactEntity fact) {
        boolean talent = fact.getKind() == CompanyFactKind.TALENT;
        return new CompanySnapshot.Fact(String.valueOf(fact.getFactId()),
                fact.getKind() == null ? null : fact.getKind().name(),
                strip(fact.getTitle()), strip(fact.getContent()),
                talent ? stripAll(fact.getPositiveSignals()) : List.of(),
                talent ? stripAll(fact.getConcernSignals()) : List.of());
    }

    /**
     * 분석 서버 계약(00 §2). 어기면 분석 서버가 재작성·피드백 요청을 422로 거부한다. 스냅샷은 면접에 고정되므로,
     * 어긴 채 만든 면접은 다시 시도해도 같은 422라 영영 시작하지 못한다. 그래서 면접을 만들기 전에 막는다.
     * 가져오기 검증을 통과한 데이터라면 생기지 않으므로, 생겼다면 데이터를 고칠 일이다.
     *
     * <p>사유는 로그에 남기는 규칙 이름이다. 값은 싣지 않는다.
     */
    static List<String> violations(CompanySnapshot snapshot) {
        List<String> violations = new ArrayList<>();
        if (!within(snapshot.displayName(), CompanyLimits.DISPLAY_NAME)) {
            violations.add("displayName");
        }
        if (snapshot.category() == null) {
            violations.add("category");
        }
        if (!within(snapshot.interviewerBrief(), CompanyLimits.INTERVIEWER_BRIEF)) {
            violations.add("interviewerBrief");
        }
        List<CompanySnapshot.Fact> facts = snapshot.facts();
        if (facts.isEmpty() || facts.size() > CompanyLimits.FACTS) {
            violations.add("facts.size");
        }
        Set<String> kinds = new HashSet<>();
        Set<String> factIds = new HashSet<>();
        for (int i = 0; i < facts.size(); i++) {
            CompanySnapshot.Fact fact = facts.get(i);
            String at = "facts[" + i + "].";
            kinds.add(fact.kind());
            if (!within(fact.factId(), FACT_ID_MAX) || !factIds.add(fact.factId())) {
                violations.add(at + "factId");
            }
            if (fact.kind() == null) {
                violations.add(at + "kind");
            }
            if (!within(fact.title(), CompanyLimits.FACT_TITLE)) {
                violations.add(at + "title");
            }
            if (!within(fact.content(), CompanyLimits.FACT_CONTENT)) {
                violations.add(at + "content");
            }
            if (!withinSignals(fact.positiveSignals()) || !withinSignals(fact.concernSignals())) {
                violations.add(at + "signals");
            }
        }
        if (!kinds.contains(CompanyFactKind.BUSINESS.name())) {
            violations.add("facts.BUSINESS");
        }
        if (!kinds.contains(CompanyFactKind.TALENT.name())) {
            violations.add("facts.TALENT");
        }
        return violations;
    }

    private static boolean withinSignals(List<String> signals) {
        return signals != null && signals.size() <= CompanyLimits.SIGNALS_PER_FACT
                && signals.stream().allMatch(signal -> within(signal, CompanyLimits.SIGNAL));
    }

    /** 1자 이상, 상한 이하. 앞뒤 공백을 뺀 값을 code point로 센다(분석 서버 Python len과 같다). */
    private static boolean within(String value, int max) {
        return value != null && !value.isBlank() && length(value) <= max;
    }

    // 앞뒤 공백만 뺀다. 검수한 내용을 길이에 맞춰 자르지 않는다.
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> stripAll(List<String> values) {
        return values == null ? null : values.stream().map(CompanySnapshotFactory::strip).toList();
    }
}
