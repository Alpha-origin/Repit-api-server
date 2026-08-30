package repit.repit_api_server.domain.metadata.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result.interview[] 한 건.
 *
 * <p>이 값은 콜백 본문 그대로 jsonb에 저장됐다가 나중에 다시 읽힌다. 그 사이 표기가 한 가지로
 * 유지된다는 보장이 없어, 두 글자 이상으로 나뉘는 필드는 snake_case와 camelCase를 모두 받는다.
 * 한쪽만 맞춰두면 다른 쪽으로 저장된 날 필드가 조용히 비고, 면접 시작은 "질문을 만들 재료가
 * 모자랍니다"로 막히는데 정작 분석 결과에는 값이 들어 있어 원인이 드러나지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedQuestionResponse {
    private Integer id;
    // tech_choice / implementation / troubleshooting / integration / structure
    private String category;
    private String question;
    @JsonAlias("expected_answer")
    private String expectedAnswer;
    // 질문의 근거 파일 경로. 추측 질문 방지 장치라 비지 않는다.
    @JsonAlias("based_on")
    private List<String> basedOn;
}
