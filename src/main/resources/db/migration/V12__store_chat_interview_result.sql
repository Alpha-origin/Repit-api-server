-- 채팅 서버가 면접을 마치고 넘겨주는 질문·답변을 실제로 담기 위한 변경.

-- 채팅 서버가 쓰는 질문 번호. 우리 PK와는 다른 체계다.
-- ORIGINAL은 분석 결과 안의 지역 번호(1..N)라 면접이 다르면 같은 번호가 다시 나오고,
-- FOLLOW는 채팅 서버가 만든 랜덤 값이다. 그래서 PK로 쓰지 않고 면접 안에서만 유일하게 둔다.
ALTER TABLE interview_question
    ADD COLUMN chat_question_id BIGINT;

ALTER TABLE interview_question
    ADD CONSTRAINT interview_question_chat_id_unique UNIQUE (interview_id, chat_question_id);

CREATE INDEX interview_question_interview_id_idx ON interview_question (interview_id);

-- 꼬리질문은 LLM이 만든다. 255자로 자르면 그대로 피드백 품질이 깎인다.
ALTER TABLE interview_question
    ALTER COLUMN content TYPE TEXT;

ALTER TABLE interview_question
    ALTER COLUMN intention TYPE TEXT;

-- 꼬리질문은 의도가 비어 올 수 있다. 여기서 막으면 면접 결과 저장이 통째로 실패하고,
-- 사용자에게는 "면접 완료"가 실패로 보인다.
ALTER TABLE interview_question
    ALTER COLUMN intention DROP NOT NULL;

CREATE INDEX interview_answer_interview_id_idx ON interview_answer (interview_id);

-- 채팅 서버는 응답 시간을 Integer로 보낸다. 비어 올 수 있어 NOT NULL을 푼다.
ALTER TABLE interview_answer
    ALTER COLUMN response_time DROP NOT NULL;
