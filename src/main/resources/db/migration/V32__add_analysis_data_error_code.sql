-- /profile 실패 콜백의 원인 코드(error.code). 실패 사유(failureReason)를 메시지 문구 대신 이것으로 고른다.
-- 분석 서버가 코드를 보내기 전에 저장된 실패와 코드 없이 온 콜백은 NULL이다. 그때는 상태 코드와 메시지로 고른다.
ALTER TABLE analysis_data
    ADD COLUMN error_code VARCHAR(40);
