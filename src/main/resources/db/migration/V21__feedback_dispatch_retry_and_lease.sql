-- 미뤄둔 채점 요청이 실패하거나 요청 도중 서버가 내려가도 자동으로 다시 시도하게 한다.
--
-- V20까지는 요청이 실패해도 DONE으로 닫았고, SENDING으로 차지한 뒤 프로세스가 죽으면 스윕이
-- WAITING만 보므로 그 면접은 영영 채점되지 않았다. 사용자는 자동 채점이 실패했다는 사실도 모른다.
--
-- attempt_count   차지할 때마다 하나씩 올린다. 한도를 넘으면 FAILED로 닫는다.
-- claimed_at      SENDING으로 차지한 시각. 오래된 SENDING은 스윕이 WAITING으로 되돌린다.
-- next_attempt_at 일시적 실패 뒤 다시 시도해도 되는 가장 이른 시각. 비어 있으면 곧바로 가능하다.
-- last_error      마지막 실패 사유. FAILED면 왜 닫혔는지가 여기 남는다.

ALTER TABLE feedback_dispatch DROP CONSTRAINT feedback_dispatch_status_check;

ALTER TABLE feedback_dispatch ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE feedback_dispatch ADD COLUMN claimed_at TIMESTAMP(6);
ALTER TABLE feedback_dispatch ADD COLUMN next_attempt_at TIMESTAMP(6);
ALTER TABLE feedback_dispatch ADD COLUMN last_error TEXT;

-- 차지한 시각이 없는 SENDING은 이 변경 전에 멈춘 건이다. 다시 기다리게 둔다.
UPDATE feedback_dispatch SET status = 'WAITING' WHERE status = 'SENDING';

ALTER TABLE feedback_dispatch
    ADD CONSTRAINT feedback_dispatch_status_check CHECK (status IN ('WAITING', 'SENDING', 'DONE', 'FAILED'));

CREATE INDEX feedback_dispatch_status_claimed_idx ON feedback_dispatch (status, claimed_at);

COMMENT ON COLUMN feedback_dispatch.status IS
    'WAITING: 영상을 기다리거나 다시 시도를 기다리는 중. SENDING: 한 곳이 차지해 채점을 요청하는 중(claimed_at 기준으로 만료된다). DONE: 채점이 접수됐거나 이미 접수돼 있었음. FAILED: 다시 시도해도 소용없거나 시도 한도를 넘어 닫음(last_error에 사유).';
