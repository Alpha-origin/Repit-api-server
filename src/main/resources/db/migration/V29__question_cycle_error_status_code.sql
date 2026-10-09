-- 사이클 실패를 코드로 가른다. 500은 LLM 출력이 구성 규칙을 어긴 것이라 다시 요청하면 풀릴 수 있다. 422는 받은 종합 데이터로는
-- 사이클을 만들 수 없다는 뜻이라(지원하지 않는 schemaVersion 등) 같은 종합 데이터로 다시 요청해도 늘 같은 422가 돌아온다.
-- 422로 거부된 종합 데이터로는 사이클을 다시 요청하지 않고, 면접을 시작할 때 저장된 자료로 종합 데이터를 다시 만든다.
ALTER TABLE question_cycle
    ADD COLUMN error_status_code INTEGER;

COMMENT ON COLUMN question_cycle.error_status_code IS
    '실패 콜백의 error.statusCode. 422면 이 종합 데이터로는 사이클을 만들 수 없어 다시 요청하지 않는다. 다시 요청할 때 비운다.';
