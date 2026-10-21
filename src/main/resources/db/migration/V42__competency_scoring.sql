-- 질문 사이클·N:1 생성 질문의 카테고리. 분석 서버가 채점 역량을 고르는 열쇠다.
-- 형식에 맞지 않는 값(옛 채팅 서버가 의도 문장을 넣어 보낸 경우)은 저장하지 않아 비어 있다.
-- 기존 행은 채우지 않는다. 옛 면접은 재채점해도 미분류로 채점된다.
ALTER TABLE interview_question ADD COLUMN category VARCHAR(64);

-- 문항별 응답 적중 등급(0~4)과 역량별 등급·근거 인용. competency-v1 이전 결과는 비어 있다.
ALTER TABLE feedback_item ADD COLUMN response_level SMALLINT;
ALTER TABLE feedback_item ADD COLUMN competencies JSONB;
