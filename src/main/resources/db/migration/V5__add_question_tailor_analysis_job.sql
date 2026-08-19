ALTER TABLE question_tailor
    ADD COLUMN analysis_job_id VARCHAR(64);

-- 채팅 서버는 분석 jobId만 들고 질문을 조회한다. 그 경로에서 재작성본을 찾을 때 쓴다.
CREATE INDEX question_tailor_analysis_job_id_idx
    ON question_tailor (analysis_job_id, created_at DESC);
