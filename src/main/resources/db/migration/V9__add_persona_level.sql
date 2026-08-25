-- 페르소나에 난이도를 다시 넣는다.
-- V7에서 제거한 자유 문자열 컬럼과 달리, 이번에는 값 집합을 EASY/NORMAL/HARD로 고정한다.
ALTER TABLE persona
    ADD COLUMN level VARCHAR(255) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE persona
    ADD CONSTRAINT persona_level_check CHECK (level IN ('EASY', 'NORMAL', 'HARD'));
