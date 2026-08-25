-- 분석 작업의 상태와 실패 사유를 남긴다.
-- 지금까지는 result 유무로만 짐작할 수 있어 "실패한 작업"과 "아직 끝나지 않은 작업"이 구분되지 않았다.
-- 구독이 콜백보다 늦게 붙어도 결과를 돌려주려면 이 구분이 필요하다.
-- 기본값을 두어 상태를 모르는 구버전이 콜백을 받아도 저장이 깨지지 않게 한다.
ALTER TABLE analysis_data
    ADD COLUMN status VARCHAR(255) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN error_status_code INTEGER,
    ADD COLUMN error_message TEXT;

-- 결과가 이미 들어 있는 기존 행은 성공한 작업이다.
UPDATE analysis_data
SET status = 'SUCCEEDED'
WHERE result IS NOT NULL;

ALTER TABLE analysis_data
    ADD CONSTRAINT analysis_data_status_check CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'));
