-- N:1 스키마에 붙은 설명을 지금 사실로 새로 심는다.
--
-- V8이 이 테이블들을 만들 때 적어둔 설명은 면접관이 3인으로 못 박혀 있던 시절의 것이다.
-- 그 뒤로 직책이 다섯 가지로 늘고(V13), 성향 값 집합이 바뀌고 어조 축이 생기고(V14),
-- 면접관 수가 2~4명으로 열렸다. V8 파일의 주석은 이미 적용된 마이그레이션이라 손댈 수
-- 없다 — 한 글자만 바꿔도 Flyway 체크섬이 어긋나 기동이 막힌다.
--
-- 마이그레이션 파일은 그 시점의 기록이므로 그대로 두는 것이 맞다. 대신 지금 맞는 설명을
-- 스키마 객체에 직접 붙인다. psql \d+ 나 DB 도구가 보여주는 것은 이쪽이라, 스키마를
-- 들여다보는 사람은 파일을 거슬러 읽지 않고도 현재 규칙을 본다.
--
-- COMMENT ON은 데이터도 구조도 건드리지 않고 같은 값을 다시 심어도 결과가 같다.

COMMENT ON TABLE interview_persona IS
    'N:1 면접에 참여하는 면접관 목록. 기술 면접관 한 명에 다른 직책이 한 명에서 세 명까지 붙어 모두 2~4명이고, 직책은 서로 겹칠 수 없다. 1:1 면접은 interview.persona_id 하나로 끝나 여기에 행이 생기지 않는다.';

COMMENT ON COLUMN interview_persona.persona_order IS
    '면접 진행 순서. 0번은 반드시 기술 면접관이다 — 원질문을 다시 쓰는 몫이 그 자리다. 나머지는 사용자가 고른 순서를 그대로 따른다. 질문 배열도 이 순서를 따르고, 꼬리질문은 부모 질문 바로 뒤에 들어간다.';

COMMENT ON COLUMN persona.role IS
    '면접관의 직책. TECH/HR/CEO/PM/DESIGN이며 성향(type), 어조(tone)와는 독립된 축이다. 질문 관점과 채점 관점을 정한다.';

COMMENT ON COLUMN persona.type IS
    '면접관의 성향. 무엇을 파고드는가를 가리킨다(FRIENDLY/REALISTIC/METICULOUS). 얼마나 세게 묻는가는 tone이 맡는다 — V14에서 두 축을 갈라냈다.';

COMMENT ON COLUMN persona.major IS
    '기술 면접관의 세부 전공(BACKEND/FRONTEND). 기술 외 직책에는 해당 값이 없어 비어 있다.';

COMMENT ON TABLE feedback_persona IS
    '면접관별 종합 피드백. N:1에만 생기는 계층이라 별도 테이블로 둔다. 면접관 한 명이 두 문항을 맡아 3지표(total/intent/reliability)를 나눌 만큼이 못 되므로 점수 하나만 둔다.';
