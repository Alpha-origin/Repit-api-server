-- 기업 맞춤 면접. 면접을 만들 때 기업 하나를 고르고, 그 기업 정보를 질문 재작성·꼬리질문·피드백에 싣는다.
--
-- 데이터는 시드 마이그레이션으로 넣지 않는다. 기동할 때 기업 데이터 패키지(JSON)를 읽어 반영한다(CompanyDataImporter).
-- 기업 행은 지우지 않는다. 면접이 company_id로 가리키고 있어서, 패키지에서 빠진 기업은 HIDDEN으로만 바꾼다.
CREATE TABLE company (
    company_id        BIGSERIAL PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL UNIQUE,
    display_name      VARCHAR(30)  NOT NULL,
    legal_name        VARCHAR(100) NOT NULL,
    aliases           JSONB        NOT NULL DEFAULT '[]',
    parent_company_id BIGINT REFERENCES company(company_id),
    category          VARCHAR(20)  NOT NULL,
    legal_size        VARCHAR(10),                       -- null = 미확인
    legal_size_source TEXT,
    dart_corp_code    VARCHAR(8)   UNIQUE,
    business_reg_no   VARCHAR(10)  UNIQUE,
    status            VARCHAR(10)  NOT NULL DEFAULT 'PUBLISHED',
    interviewer_brief TEXT         NOT NULL,
    data_as_of        DATE         NOT NULL,
    reviewed_by       VARCHAR(50),
    reviewed_at       DATE,
    content_hash      VARCHAR(64)  NOT NULL,             -- 가져온 패키지 파일의 SHA-256
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE company_source (
    source_id    BIGSERIAL PRIMARY KEY,
    company_id   BIGINT       NOT NULL REFERENCES company(company_id),
    source_key   VARCHAR(40)  NOT NULL,
    source_type  VARCHAR(20)  NOT NULL,
    url          TEXT         NOT NULL,
    title        VARCHAR(200),
    scope        VARCHAR(100),
    published_at DATE,
    collected_at DATE         NOT NULL,
    UNIQUE (company_id, source_key)
);

CREATE TABLE company_fact (
    fact_id          BIGSERIAL PRIMARY KEY,
    company_id       BIGINT       NOT NULL REFERENCES company(company_id),
    kind             VARCHAR(20)  NOT NULL,
    title            VARCHAR(20)  NOT NULL,
    content          VARCHAR(200) NOT NULL,
    positive_signals JSONB        NOT NULL DEFAULT '[]',
    concern_signals  JSONB        NOT NULL DEFAULT '[]',
    signals_origin   VARCHAR(10),
    source_id        BIGINT       NOT NULL REFERENCES company_source(source_id),
    sort_order       INT          NOT NULL
);
CREATE INDEX idx_company_fact_company ON company_fact(company_id, sort_order);

-- 면접은 만들 때의 기업 정보를 스냅샷으로 들고 있다. 이후 모든 경로는 스냅샷만 읽는다.
ALTER TABLE interview ADD COLUMN company_id BIGINT REFERENCES company(company_id);
ALTER TABLE interview ADD COLUMN company_snapshot JSONB;
ALTER TABLE feedback  ADD COLUMN company_fit JSONB;
