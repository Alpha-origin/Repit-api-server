-- 페르소나에서 더 이상 쓰지 않는 난이도(level) 컬럼을 제거한다.
-- 베이스라인(V1) 이전에 생성된 DB에만 남아 있을 수 있어 IF EXISTS로 처리한다.
ALTER TABLE persona
    DROP COLUMN IF EXISTS level;
