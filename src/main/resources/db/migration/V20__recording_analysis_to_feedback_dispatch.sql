-- 녹화 영상을 따로 분석하지 않고 피드백 요청에 함께 싣는다. V19의 녹화 분석 기록을
-- "피드백 요청을 영상이 모일 때까지 미뤄두는 자리"로 바꾼다.
--
-- 분석 서버에 별도 작업을 맡기지 않으므로 job_id, 실패 사유, 요청 시각이 필요 없다. 접수 결과와
-- 실패는 feedback 테이블이 이미 남긴다. 상태도 기다리는 중 / 보내는 중 / 끝남 셋으로 줄인다.
--
-- 이미 보냈거나(PENDING), 실패했거나(FAILED), 건너뛴(SKIPPED) 건은 그 시점에 피드백 요청이
-- 별도로 나갔으므로 끝난 것으로 옮긴다. 보내는 중에 멈춘 건(SENDING)도 되살릴 방법이 없어 끝낸다.
-- 아직 기다리던 건(WAITING)은 그대로 두면 스윕이 피드백 요청으로 이어 보내고, 이미 피드백이
-- 접수된 면접이면 FeedbackService가 건너뛴다.

ALTER TABLE recording_analysis DROP CONSTRAINT recording_analysis_status_check;

UPDATE recording_analysis SET status = 'DONE' WHERE status <> 'WAITING';

ALTER TABLE recording_analysis RENAME TO feedback_dispatch;
ALTER TABLE feedback_dispatch RENAME COLUMN analysis_id TO dispatch_id;
ALTER TABLE feedback_dispatch RENAME CONSTRAINT recording_analysis_pkey TO feedback_dispatch_pkey;
ALTER TABLE feedback_dispatch RENAME CONSTRAINT recording_analysis_interview_unique TO feedback_dispatch_interview_unique;
ALTER INDEX recording_analysis_status_activity_idx RENAME TO feedback_dispatch_status_activity_idx;

ALTER TABLE feedback_dispatch DROP COLUMN job_id;
ALTER TABLE feedback_dispatch DROP COLUMN error_message;
ALTER TABLE feedback_dispatch DROP COLUMN requested_at;

ALTER TABLE feedback_dispatch
    ADD CONSTRAINT feedback_dispatch_status_check CHECK (status IN ('WAITING', 'SENDING', 'DONE'));

COMMENT ON TABLE feedback_dispatch IS
    '면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미뤄두는 자리. 면접당 한 행이고 한 번만 요청한다. 접수 결과는 feedback 테이블에 남는다.';

COMMENT ON COLUMN feedback_dispatch.status IS
    'WAITING: 기록은 받았고 영상을 기다리는 중. SENDING: 한 곳이 차지해 피드백을 요청하는 중. DONE: 요청을 마침(접수 성공·실패는 feedback 테이블에서 본다).';

COMMENT ON COLUMN feedback_dispatch.last_activity_at IS
    '기록이나 영상이 마지막으로 들어온 시각. 영상이 덜 모였을 때 이 시각부터 유예 시간이 지나면 있는 영상만 실어 요청한다.';
