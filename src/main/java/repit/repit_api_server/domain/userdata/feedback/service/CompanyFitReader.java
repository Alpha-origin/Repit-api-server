package repit.repit_api_server.domain.userdata.feedback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompanyFitResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static repit.repit_api_server.domain.company.CompanyLimits.length;

/**
 * 채점 콜백의 companyFit을 그 면접의 스냅샷과 그 작업의 대상 문항에 대조해 읽는다(00 §6).
 *
 * <p>{@link ScoreBreakdownReader}와 같은 이유로 요청 본문에서는 모양을 가리지 않고 받아 여기서 푼다. 타입을 정해 받으면
 * 모양이 하나만 어긋나도 본문 전체를 읽지 못해 채점 결과까지 잃는다. 기업 관점 코멘트는 없어도 피드백이 성립하는 부가
 * 정보라, 받아들일 수 없으면 null로 두고 기본 피드백은 그대로 저장한다.
 *
 * <ul>
 *   <li>기업을 고르지 않은 면접, null, 모양이 틀린 값은 null이다.</li>
 *   <li>comment는 앞뒤 공백을 뺀 1~300자. 비었거나 넘으면 전체 null이다 — 잘라 저장하지 않는다.</li>
 *   <li>항목은 스냅샷의 factId만 받는다. kind·focus는 모델 값을 버리고 스냅샷 fact의 kind·title로 채운다.</li>
 *   <li>evidence·suggestion은 1~150자. 어기면 그 항목을 뺀다.</li>
 *   <li>questionIds는 그 작업에 보낸 답변 있는 문항만 남기고 겹친 것을 뺀다. 하나도 안 남으면 그 항목을 뺀다.</li>
 *   <li>한 목록 안에서 같은 factId는 첫 유효 항목만 둔다. matchedFocus와 gaps가 같은 fact를 가리키는 것은 괜찮다.</li>
 *   <li>목록마다 유효 항목 앞의 세 개까지 둔다.</li>
 *   <li>항목이 있었는데 모두 빠지면 전체 null이다 — 근거 없는 총평만 살리지 않는다. 처음부터 두 목록이 비었으면 총평만 둔다.</li>
 * </ul>
 *
 * <p>로그에는 면접 id와 사유·개수만 남긴다. 모델이 쓴 문장은 남기지 않는다.
 */
final class CompanyFitReader {

    private static final Logger log = LoggerFactory.getLogger(CompanyFitReader.class);

    static final int COMMENT_MAX = 300;
    static final int DETAIL_MAX = 150;
    static final int ITEMS_MAX = 3;

    private CompanyFitReader() {
    }

    /**
     * @param snapshot            그 면접의 기업 스냅샷. 피드백 요청에 실은 것과 같다. 기업을 고르지 않았으면 null이다.
     * @param answeredQuestionIds 그 작업에 보낸 문항 가운데 답변이 있는 것의 id. 보낸 문자열 그대로다.
     */
    static CompanyFitResponse read(JsonNode node, CompanySnapshot snapshot, Set<String> answeredQuestionIds,
                                   Long interviewId) {
        // 기업을 고르지 않은 면접이거나, 분석 서버가 만들지 못했으면 null로 온다.
        if (node == null || node.isNull() || node.isMissingNode()) {
            if (snapshot != null) {
                // 기업을 골랐는데 오지 않았다. 우리 쪽 검증에서 버린 것과 가려야 분석 서버 로그로 넘어갈지 정할 수 있다.
                log.warn("기업을 고른 면접인데 기업 관점 코멘트가 오지 않았습니다. interviewId={}, 사유=FIT_NOT_RECEIVED, 기업 fact={}건",
                        interviewId, snapshot.facts() == null ? 0 : snapshot.facts().size());
            }
            return null;
        }
        if (snapshot == null) {
            log.warn("기업을 고르지 않은 면접에 기업 관점 코멘트가 와서 버립니다. interviewId={}", interviewId);
            return null;
        }
        if (!node.isObject()) {
            return rejected(interviewId, "NOT_OBJECT");
        }

        String comment = text(node.get("comment"));
        if (comment == null || comment.isEmpty()) {
            return rejected(interviewId, "EMPTY_COMMENT");
        }
        if (length(comment) > COMMENT_MAX) {
            return rejected(interviewId, "COMMENT_TOO_LONG");
        }

        JsonNode matchedNode = node.get("matchedFocus");
        JsonNode gapsNode = node.get("gaps");
        if (!isListOrAbsent(matchedNode) || !isListOrAbsent(gapsNode)) {
            return rejected(interviewId, "NOT_LIST");
        }

        Map<String, CompanySnapshot.Fact> facts = snapshot.facts().stream()
                .collect(Collectors.toMap(CompanySnapshot.Fact::factId, Function.identity(), (a, b) -> a));
        Map<String, Integer> dropped = new TreeMap<>();
        List<CompanyFitResponse.Item> matched = readItems(matchedNode, "evidence", facts, answeredQuestionIds, dropped,
                (fact, detail, ids) -> new CompanyFitResponse.Item(fact.factId(), fact.kind(), fact.title(), detail, ids));
        List<CompanyFitResponse.Gap> gaps = readItems(gapsNode, "suggestion", facts, answeredQuestionIds, dropped,
                (fact, detail, ids) -> new CompanyFitResponse.Gap(fact.factId(), fact.kind(), fact.title(), detail, ids));

        // 대상 문항이 0건이면 원시 코멘트가 맞아도 모든 항목이 NO_ANSWERED_QUESTION으로 빠진다. 그 경우를 가리려고 함께 남긴다.
        int received = size(matchedNode) + size(gapsNode);
        if (received > 0 && matched.isEmpty() && gaps.isEmpty()) {
            log.warn("기업 관점 코멘트의 항목이 모두 근거가 맞지 않아 코멘트 전체를 비워 둡니다. interviewId={}, 받은 항목={}, 대상 문항={}건, 사유={}",
                    interviewId, received, answeredQuestionIds.size(), dropped);
            return null;
        }
        if (!dropped.isEmpty()) {
            log.warn("기업 관점 코멘트에서 근거가 맞지 않는 항목을 뺐습니다. interviewId={}, 받은 항목={}, 남은 항목={}, 대상 문항={}건, 사유={}",
                    interviewId, received, matched.size() + gaps.size(), answeredQuestionIds.size(), dropped);
        }
        return new CompanyFitResponse(comment, matched, gaps);
    }

    private interface ItemFactory<T> {
        T create(CompanySnapshot.Fact fact, String detail, List<String> questionIds);
    }

    private static <T> List<T> readItems(JsonNode list, String detailField, Map<String, CompanySnapshot.Fact> facts,
                                         Set<String> answeredQuestionIds, Map<String, Integer> dropped,
                                         ItemFactory<T> factory) {
        List<T> items = new ArrayList<>();
        if (list == null || list.isNull()) {
            return items;
        }
        Set<String> usedFactIds = new HashSet<>();
        for (JsonNode element : list) {
            String reason = null;
            CompanySnapshot.Fact fact = null;
            String detail = null;
            List<String> questionIds = List.of();
            if (element == null || !element.isObject()) {
                reason = "ITEM_NOT_OBJECT";
            } else {
                fact = facts.get(id(element.get("factId")));
                detail = text(element.get(detailField));
                questionIds = questionIds(element.get("questionIds"), answeredQuestionIds);
                if (fact == null) {
                    reason = "UNKNOWN_FACT";
                } else if (detail == null || detail.isEmpty() || length(detail) > DETAIL_MAX) {
                    reason = "INVALID_" + detailField.toUpperCase(Locale.ROOT);
                } else if (questionIds.isEmpty()) {
                    reason = "NO_ANSWERED_QUESTION";
                } else if (usedFactIds.contains(fact.factId())) {
                    reason = "DUPLICATE_FACT";
                } else if (items.size() >= ITEMS_MAX) {
                    reason = "TOO_MANY";
                }
            }
            if (reason != null) {
                dropped.merge(reason, 1, Integer::sum);
                continue;
            }
            usedFactIds.add(fact.factId());
            items.add(factory.create(fact, detail, questionIds));
        }
        return items;
    }

    // 보낸 문항 id 그대로 비교한다. "q2"를 숫자로 바꾸거나 양수만 받지 않는다. 순서는 받은 대로 두고 겹친 것만 뺀다.
    private static List<String> questionIds(JsonNode node, Set<String> answeredQuestionIds) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (JsonNode element : node) {
            String questionId = id(element);
            if (questionId != null && answeredQuestionIds.contains(questionId)) {
                kept.add(questionId);
            }
        }
        return List.copyOf(kept);
    }

    // id는 문자열 계약이지만 정수로 와도 같은 글자로 받는다.
    private static String id(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isIntegralNumber()) {
            return node.asString();
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue().strip() : null;
    }

    private static boolean isListOrAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isArray();
    }

    private static int size(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static CompanyFitResponse rejected(Long interviewId, String reason) {
        log.warn("기업 관점 코멘트를 받아들일 수 없어 비워 둡니다. interviewId={}, 사유={}", interviewId, reason);
        return null;
    }
}
