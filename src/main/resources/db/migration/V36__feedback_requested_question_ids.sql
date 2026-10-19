-- 기업 관점 코멘트(companyFit)가 근거로 댈 수 있는 문항을 채점 요청 시점의 것으로 고정한다(00 §6).
-- 콜백 때 면접 기록에서 다시 뽑으면, 요청 뒤 답변 API로 더해진 답변의 문항까지 근거로 통과한다.

-- 이 채점 작업에 실어 보낸 문항 가운데 답변이 있는 것의 id. 접수 응답을 받아 행을 만들 때 넣는다.
ALTER TABLE feedback ADD COLUMN requested_question_ids JSONB;

-- 가장 최근 채점 요청에 실은 같은 목록. 보내기 직전에 남긴다. 콜백이 접수 응답보다 먼저 와 작업 행에 목록이 없을 때 쓴다.
ALTER TABLE interview ADD COLUMN last_feedback_question_ids JSONB;
