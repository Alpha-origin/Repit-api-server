package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 분석 서버 POST /questions/cycle 요청. 종합 데이터만 보고 한 사이클(세트 3개)을 만든다.
 *
 * <p>분석 서버는 저장소가 없어 겹치지 말아야 할 질문도 우리가 실어 보낸다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionCycleRequest {
    // SOLO / MULTI
    private String mode;
    // /profile 결과의 profile을 그대로 넘긴다.
    private Object profile;
    // 같은 모드의 최근 2사이클 질문 본문. 최대 30개.
    private List<String> excludeQuestions;
    private String callbackUrl;
}
