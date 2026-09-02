-- 면접관 성향 값 집합을 바꾸고, 성향과 독립된 어조 축을 신설한다.

-- 성향은 "무엇을 파고드는가"다. NEUTRAL/STRESS는 파고드는 대상이 아니라 세기를 가리키고 있어
-- 아래에서 새로 만드는 어조(tone)와 축이 겹쳤다. 성향은 관점 이름으로 바꾸고 세기는 tone이 맡는다.
-- 기존 행을 옮기려면 제약을 먼저 풀어야 한다. 새 값도 옛 제약에는 걸린다.
ALTER TABLE persona
    DROP CONSTRAINT persona_type_check;

UPDATE persona SET type = 'REALISTIC' WHERE type = 'NEUTRAL';
UPDATE persona SET type = 'METICULOUS' WHERE type = 'STRESS';

ALTER TABLE persona
    ADD CONSTRAINT persona_type_check CHECK (type IN ('FRIENDLY', 'REALISTIC', 'METICULOUS'));

-- 어조. 꼼꼼한 면접관이 부드럽게 물을 수도, 친화적인 면접관이 몰아붙일 수도 있어야 한다.
-- 기존 행에는 판단 근거가 없으므로 중립값인 DIRECT로 채운다. 신규 등록은 API에서 필수로 막는다.
ALTER TABLE persona
    ADD COLUMN tone VARCHAR(255) NOT NULL DEFAULT 'DIRECT';

ALTER TABLE persona
    ADD CONSTRAINT persona_tone_check CHECK (tone IN ('GENTLE', 'DIRECT', 'PRESSURING'));
