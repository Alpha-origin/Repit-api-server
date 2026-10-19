-- 기업 데이터셋 적용(00 공통 계약, 02 API).

-- 출처가 모두 SAMPLE인 개발용 패키지. 운영에서는 가져오지도, 목록·면접 생성에 내보내지도 않는다(app.company.allow-sample).
ALTER TABLE company ADD COLUMN sample BOOLEAN NOT NULL DEFAULT false;
UPDATE company c
   SET sample = NOT EXISTS (SELECT 1
                              FROM company_source s
                             WHERE s.company_id = c.company_id
                               AND s.source_type <> 'SAMPLE');

-- fact는 자기 기업의 출처만 가리킨다. 출처 id만 걸면 다른 기업의 출처를 가리켜도 DB가 받아 준다.
ALTER TABLE company_source ADD CONSTRAINT uq_company_source_company_source UNIQUE (company_id, source_id);
ALTER TABLE company_fact ADD CONSTRAINT fk_company_fact_own_source
    FOREIGN KEY (company_id, source_id) REFERENCES company_source (company_id, source_id);

-- 면접 생성 중복 방지. 같은 사용자가 같은 키로 다시 보내면 처음 만든 면접을 돌려준다.
-- 키 등록과 면접 저장은 한 트랜잭션이라 면접 없는 키는 남지 않는다. 면접을 지우면 키도 함께 지운다.
CREATE TABLE interview_create_request (
    interview_create_request_id BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT      NOT NULL,
    idempotency_key             UUID        NOT NULL,
    -- 정규화한 생성 설정(mode·면접관·companyId)의 SHA-256. 같은 키에 다른 설정이 오면 409다.
    request_hash                VARCHAR(64) NOT NULL,
    interview_id                BIGINT      NOT NULL REFERENCES interview (interview_id) ON DELETE CASCADE,
    created_at                  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_interview_create_request_user_key UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_interview_create_request_interview ON interview_create_request (interview_id);
