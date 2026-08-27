-- N:1 연동 요청서(A-1) 반영. 페르소나 카드에 필요한 값과 늘어난 직책 집합을 담는다.

-- 카드 사진·설명이 API에 없어 프론트에 하드코딩돼 있었다.
ALTER TABLE persona
    ADD COLUMN image_url VARCHAR(2083);

ALTER TABLE persona
    ADD COLUMN description TEXT;

-- 직책을 기술·인사·CEO 셋으로 고정해두면 기획·디자인 면접관을 넣을 수 없다.
-- 분석 서버는 hr/ceo/pm/design 키로 질문 관점을 찾고 모르는 값은 기본 지침으로 폴백하므로,
-- 값 집합은 영문 대문자 상수로 통일해 여기서 한 번에 정한다.
ALTER TABLE persona
    DROP CONSTRAINT persona_role_check;

ALTER TABLE persona
    ADD CONSTRAINT persona_role_check CHECK (role IN ('TECH', 'HR', 'CEO', 'PM', 'DESIGN'));

-- 질문 준비 작업이 1:1 재작성인지 N:1 구성인지. 실패했을 때 갈 길이 서로 다르다.
-- 1:1은 원질문으로 폴백해 면접을 열지만, N:1의 신규 질문 4개는 분석 서버 말고는 만들 데가 없어
-- 폴백할 원질문 자체가 없다. 이 구분이 없으면 실패한 N:1이 기술 질문 2개짜리 면접으로 열린다.
ALTER TABLE question_tailor
    ADD COLUMN mode VARCHAR(255) NOT NULL DEFAULT 'SOLO';

ALTER TABLE question_tailor
    ADD CONSTRAINT question_tailor_mode_check CHECK (mode IN ('SOLO', 'MULTI'));
