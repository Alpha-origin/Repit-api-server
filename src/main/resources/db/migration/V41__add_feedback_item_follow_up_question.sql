-- 문항별 예상 꼬리질문. 분석 서버가 그 질문과 실제 답변을 보고 만든 한 문장이다.
-- 이전 피드백과 이 필드를 보내지 않는 분석 서버의 콜백은 값이 없다.
ALTER TABLE feedback_item ADD COLUMN follow_up_question TEXT;
