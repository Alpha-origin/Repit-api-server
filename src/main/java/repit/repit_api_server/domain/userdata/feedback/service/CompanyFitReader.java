package repit.repit_api_server.domain.userdata.feedback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompanyFitResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 채점 콜백의 companyFit을 읽는다.
 *
 * <p>{@link ScoreBreakdownReader}와 같은 이유로 요청 본문에서는 모양을 가리지 않고 받아 여기서 푼다. 타입을 정해
 * 받으면 모양이 하나만 어긋나도 본문 전체를 읽지 못해 채점 결과까지 잃는다. 기업 관점 코멘트는 없어도 피드백이
 * 성립하는 부가 정보라, 읽지 못하면 비워 두고 어긋난 사실을 로그로 남긴다.
 */
final class CompanyFitReader {

    private static final Logger log = LoggerFactory.getLogger(CompanyFitReader.class);

    private CompanyFitReader() {
    }

    static CompanyFitResponse read(JsonNode node, Long interviewId) {
        // 기업을 고르지 않은 면접이거나, 분석 서버가 만들지 못했으면 null로 온다.
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            CompanyFitResponse fit = JsonMapper.shared().treeToValue(node, CompanyFitResponse.class);
            if (fit.comment() == null || fit.comment().isBlank()) {
                log.warn("기업 관점 코멘트가 비어 있어 비워 둡니다. interviewId={}, 받은 값={}", interviewId, node);
                return null;
            }
            return fit;
        } catch (JacksonException e) {
            log.warn("기업 관점 코멘트를 읽지 못해 비워 둡니다. interviewId={}, 받은 값={}, 오류={}",
                    interviewId, node, e.getOriginalMessage());
            return null;
        }
    }
}
