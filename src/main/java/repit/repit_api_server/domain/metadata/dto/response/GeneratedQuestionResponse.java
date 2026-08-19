package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result.interview[] 한 건.
 * 분석 서버 쪽 와이어 포맷이 snake_case라 필드명을 그대로 맞춘다.
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
    private String expected_answer;
    // 질문의 근거 파일 경로. 추측 질문 방지 장치라 비지 않는다.
    private List<String> based_on;
}
