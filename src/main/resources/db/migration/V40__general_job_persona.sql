ALTER TABLE persona DROP CONSTRAINT persona_major_check;
ALTER TABLE persona ADD CONSTRAINT persona_major_check CHECK (major IN (
    'FRONTEND', 'BACKEND', 'WEB_PUBLISHER', 'DATA_ANALYST', 'DBA_INFRA', 'QA_ENGINEER', 'SECURITY', 'PRODUCT_MANAGER', 'IT_CONSULTANT', 'GEN_STRATEGY', 'GEN_HR_ADMIN', 'GEN_FINANCE', 'GEN_MARKETING', 'GEN_SALES', 'GEN_DESIGN'));

ALTER TABLE persona ADD COLUMN job_title VARCHAR(30) NULL;
COMMENT ON COLUMN persona.job_title IS '일반 직군 기술(첫 자리) 면접관의 지원자 직무명. 면접 시작 시점 값. 그 밖은 NULL';

ALTER TABLE persona ADD COLUMN interview_snapshot BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN persona.interview_snapshot IS '일반 직군 면접 시작 시점 전용 면접관. 선택 목록에서는 숨김';
ALTER TABLE persona ADD COLUMN snapshot_source_name VARCHAR(255) NULL;
