package repit.repit_api_server.domain.userdata.feedback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompetencyScoreResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static repit.repit_api_server.domain.userdata.feedback.service.ScoreBreakdownReader.QUOTE_MAX;
import static repit.repit_api_server.domain.userdata.feedback.service.ScoreBreakdownReader.isAbsent;
import static repit.repit_api_server.domain.userdata.feedback.service.ScoreBreakdownReader.length;
import static repit.repit_api_server.domain.userdata.feedback.service.ScoreBreakdownReader.wholeNumber;

/**
 * 채점 콜백 문항의 응답 적중 등급과 역량별 근거(competency-v1)를 읽는다.
 *
 * <p>{@link ScoreBreakdownReader}와 같은 이유로 요청 본문에서는 모양을 가리지 않고 받아 여기서 푼다. 한 필드 모양이
 * 어긋나 본문 전체가 400이 되면 분석 서버가 재시도 뒤 결과를 영구 폐기한다. 어긋난 항목과 인용만 버리고, 문항의 나머지
 * 평가와 점수는 그대로 저장한다.
 *
 * <p>인용은 사용자 답변의 원문이다. 로그에는 사유와 길이만 남기고 인용 자체는 남기지 않는다.
 */
final class CompetencyReader {

    private static final Logger log = LoggerFactory.getLogger(CompetencyReader.class);

    private static final Set<String> COMPETENCIES = Set.of(
            "PROBLEM_SOLVING", "KNOWLEDGE", "OWNERSHIP", "COLLABORATION", "GROWTH", "MOTIVATION");
    private static final Set<String> ROLES = Set.of("PRIMARY", "SECONDARY");
    private static final int LEVEL_MAX = 4;
    private static final int SCORE_MAX = 100;
    // 근거 종류 이름(reason, mechanism 등)의 최대 글자 수.
    private static final int SLOT_MAX = 40;

    private CompetencyReader() {
    }

    /** 응답 적중 등급. 0..4 정수만 받고 그 밖의 값은 비운다. */
    static Integer readLevel(JsonNode node, String label, Long interviewId) {
        if (isAbsent(node)) {
            return null;
        }
        Integer level = wholeNumber(node);
        if (level != null && level >= 0 && level <= LEVEL_MAX) {
            return level;
        }
        log.warn("{} 응답 적중 등급이 0..{} 정수가 아니라 비워 둡니다. interviewId={}, 받은 값={}",
                label, LEVEL_MAX, interviewId, node);
        return null;
    }

    /**
     * 점수에 들어간 역량 목록. 배열이 아니면 비운다.
     *
     * <p>역량 코드·역할·등급·점수 중 하나라도 어긋난 항목은 버린다. 무엇을 몇 점으로 매겼는지 모르는 항목은 화면에 그릴 수
     * 없다. 근거 인용은 어긋난 것만 버리고 항목은 남긴다 — 인용이 없어도 등급은 보여 줄 수 있다.
     */
    static List<CompetencyScoreResponse> read(JsonNode node, String label, Long interviewId) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isArray()) {
            log.warn("{} 역량 목록이 배열이 아니라 비워 둡니다. interviewId={}, 받은 모양={}",
                    label, interviewId, node.getNodeType());
            return null;
        }
        List<CompetencyScoreResponse> competencies = new ArrayList<>();
        int index = 0;
        for (JsonNode element : node.values()) {
            CompetencyScoreResponse competency = readCompetency(element, label + " 역량 " + index++, interviewId);
            if (competency != null) {
                competencies.add(competency);
            }
        }
        return competencies;
    }

    private static CompetencyScoreResponse readCompetency(JsonNode node, String label, Long interviewId) {
        if (!node.isObject()) {
            log.warn("{} 항목이 객체가 아니라 버립니다. interviewId={}, 받은 모양={}", label, interviewId, node.getNodeType());
            return null;
        }
        String competency = code(node.get("competency"), COMPETENCIES);
        String role = code(node.get("role"), ROLES);
        Integer level = ranged(node.get("level"), LEVEL_MAX);
        Integer score = ranged(node.get("score"), SCORE_MAX);
        if (competency == null || role == null || level == null || score == null) {
            log.warn("{} 항목을 버립니다. interviewId={}, competency={}, role={}, level={}, score={}",
                    label, interviewId, node.get("competency"), node.get("role"), node.get("level"), node.get("score"));
            return null;
        }
        return new CompetencyScoreResponse(competency, role, level, score,
                readEvidence(node.get("evidence"), label + "(" + competency + ")", interviewId));
    }

    /** 근거 인용. 근거 종류는 1..40자, 인용은 1..1000자 문자열이어야 한다. 어긋난 인용만 버린다. */
    private static List<CompetencyScoreResponse.Evidence> readEvidence(JsonNode node, String label, Long interviewId) {
        if (isAbsent(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            log.warn("{} 근거 인용 목록이 배열이 아니라 비워 둡니다. interviewId={}, 받은 모양={}",
                    label, interviewId, node.getNodeType());
            return List.of();
        }
        List<CompetencyScoreResponse.Evidence> evidence = new ArrayList<>();
        for (JsonNode element : node.values()) {
            String slot = element.isObject() ? string(element.get("slot"), SLOT_MAX) : null;
            String quote = element.isObject() ? string(element.get("quote"), QUOTE_MAX) : null;
            if (slot == null || quote == null) {
                log.warn("{} 근거 인용 하나를 버립니다. interviewId={}, 받은 모양={}, 근거 종류 길이={}, 인용 길이={}",
                        label, interviewId, element.getNodeType(),
                        element.isObject() ? lengthOf(element.get("slot")) : null,
                        element.isObject() ? lengthOf(element.get("quote")) : null);
                continue;
            }
            evidence.add(new CompetencyScoreResponse.Evidence(slot, quote));
        }
        return evidence;
    }

    private static String code(JsonNode node, Set<String> allowed) {
        return node != null && node.isString() && allowed.contains(node.stringValue()) ? node.stringValue() : null;
    }

    private static Integer ranged(JsonNode node, int max) {
        Integer value = wholeNumber(node);
        return value != null && value >= 0 && value <= max ? value : null;
    }

    // 비지 않고 maxLength(글자 수) 이하인 문자열. 원문 인용이라 공백을 다듬지 않는다.
    private static String string(JsonNode node, int maxLength) {
        if (node == null || !node.isString() || node.stringValue().isBlank()
                || length(node.stringValue()) > maxLength) {
            return null;
        }
        return node.stringValue();
    }

    // 로그에 원문 대신 남기는 길이. 문자열이 아니면 -1이다.
    private static Integer lengthOf(JsonNode node) {
        if (isAbsent(node)) {
            return null;
        }
        return node.isString() ? length(node.stringValue()) : -1;
    }
}
