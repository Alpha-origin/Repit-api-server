-- 음성 분석 결과의 실패 사유를 코드로 가릴 수 있게 한다.
--
-- 분석 서버는 실패를 {code, message, retryable}로 알린다. message는 코드마다 같은 문구라 분기에 쓸 수 없고,
-- 새 URL로 다시 맡겨 살릴 수 있는 실패(SOURCE_ACCESS_DENIED_OR_EXPIRED 등)와 다시 해도 같은 실패
-- (FFPROBE_FAILED 등)를 가르는 값이 retryable이다. 전문을 error에 남기지만, 그것만으로는 코드별로 묶어
-- 볼 수 없어 두 값을 따로 둔다.
ALTER TABLE audio_analysis_result ADD COLUMN error_code VARCHAR(100);
ALTER TABLE audio_analysis_result ADD COLUMN error_retryable BOOLEAN;

COMMENT ON COLUMN audio_analysis_result.error_code IS
    '분석 서버가 알린 실패 코드. 표에 없는 코드도 올 수 있어 그대로 둔다. 성공한 녹음은 비어 있다.';
COMMENT ON COLUMN audio_analysis_result.error_retryable IS
    '참이면 새 요청으로 살릴 수 있는 실패다. 분석 서버가 스스로 다시 시도하고 있다는 뜻은 아니다.';

-- 접수된 요청과 아직 답을 받지 못한 요청은 job_id로 갈린다.
COMMENT ON COLUMN audio_analysis.job_id IS
    '분석 서버가 접수하며 발급한 작업 id. 비어 있으면 보냈지만 202를 받지 못한 요청이라, 같은 request_id로 다시 보내 접수를 확인한다.';
