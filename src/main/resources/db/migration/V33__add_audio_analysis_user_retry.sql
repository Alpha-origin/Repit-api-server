-- 사용자가 음성 분석을 다시 요청할 수 있게 한다(D3). 면접당 정해진 횟수까지만 받는다.
--
-- 횟수는 음성 분석 대기 행(kind = AUDIO_ANALYSIS)에 둔다. 한도 확인, 횟수 증가, 대기 행을 다시 기다리는 자리로
-- 돌리는 일을 한 문장의 조건부 갱신으로 해야 연달아 누른 요청이 둘 다 받아들여지거나 한도를 넘지 않는다.
ALTER TABLE feedback_dispatch ADD COLUMN user_retry_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN feedback_dispatch.user_retry_count IS
    '사용자가 음성 분석을 다시 요청한 횟수. AUDIO_ANALYSIS 행만 쓴다. 면접당 2회까지 받는다.';
