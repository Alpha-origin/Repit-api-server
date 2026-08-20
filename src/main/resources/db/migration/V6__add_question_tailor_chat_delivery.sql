-- 재작성 결과가 확정되면 이 서버가 채팅 서버로 면접 데이터를 밀어넣는다.
-- 콜백이 재전송돼도 중복 전달하지 않도록 전달 여부를 남긴다.
ALTER TABLE question_tailor
    ADD COLUMN chat_delivered BOOLEAN,
    ADD COLUMN chat_error_message TEXT;

-- 채팅 서버가 분석 jobId로 질문을 되가져가던 경로가 없어져 더 이상 이 인덱스로 조회하지 않는다.
-- analysis_job_id 컬럼 자체는 채팅 서버로 함께 넘기므로 남긴다.
DROP INDEX IF EXISTS question_tailor_analysis_job_id_idx;
