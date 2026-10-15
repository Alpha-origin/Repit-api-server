package repit.repit_api_server.domain.company.dto;

/** 채팅 서버 준비 요청의 company(00 §6-3). 꼬리질문은 이 요약만으로 기업을 반영한다. */
public record ChatCompany(String displayName, String interviewerBrief) {
}
