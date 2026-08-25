-- 콜백을 저장한 시각. "이번 실행의 결과"와 "지난 실행에 남아 있던 결과"를 가르는 기준이다.
-- 같은 job_id로 분석을 다시 요청하면 예전 결과가 그대로 남아, 구독이 붙는 순간
-- 분석 서버가 콜백을 보내기도 전에 옛 결과가 완료 이벤트로 나갔다.
ALTER TABLE analysis_data
    ADD COLUMN completed_at TIMESTAMP(6);

-- 이미 끝나 있던 행은 모두 지난 실행의 결과다. 끝난 시각을 알 수 없으니 생성 시각으로 둔다.
-- 새 요청의 requested_at은 이보다 항상 뒤라서 지난 결과로 올바르게 판정된다.
UPDATE analysis_data
SET completed_at = created_at
WHERE status <> 'PENDING';
