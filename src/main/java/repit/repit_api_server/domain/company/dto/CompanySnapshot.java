package repit.repit_api_server.domain.company.dto;

import java.util.List;

/**
 * 면접을 만들 때 복사해 두는 기업 정보. interview.company_snapshot에 이 모양 그대로 저장되고,
 * 분석 서버 요청의 company(00 §6-1)에도 그대로 실린다 — 변환 코드를 따로 두지 않는다.
 *
 * <p>규모·별칭·출처·signalsOrigin은 프롬프트에 필요 없어 넣지 않는다. 기업 정보가 나중에 바뀌어도
 * 지난 면접과 피드백은 이 스냅샷 기준으로 남는다.
 */
public record CompanySnapshot(
        String companyId, String displayName, String category,
        List<Fact> facts, String interviewerBrief) {

    // 신호는 TALENT에만 있다. 나머지는 빈 목록이고, 분석 서버는 그것을 "신호 없음"으로 받는다.
    public record Fact(String factId, String kind, String title, String content,
                       List<String> positiveSignals, List<String> concernSignals) {
    }

    // 채팅 서버는 실시간 경로라 요약만 받는다.
    public ChatCompany toChat() {
        return new ChatCompany(displayName, interviewerBrief);
    }
}
