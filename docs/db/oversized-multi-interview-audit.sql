-- N:1 면접관 상한을 기술 외 4명에서 3명으로 좁히기 전에 돌려보는 점검 질의.
--
-- 상한은 면접을 "만들 때"만 본다. 이미 만들어진 면접은 인원이 그대로 남아,
-- 분석 서버 /questions/tailor/multi 의 otherPersonas 상한이 함께 좁혀지면
-- 그 면접의 질문 준비·재시도가 422로 떨어진다.
--
-- 영향을 받는 경로는 질문 준비 하나뿐이다.
--   - 생성    : 상한을 보는 유일한 자리. 좁히면 새 면접만 2~4명이 된다.
--   - 준비    : 우리 쪽에 상한 검사가 없어 그대로 분석 서버까지 간다. << 영향 있음
--   - 진행    : 채팅 서버 인계는 면접관 수를 보지 않는다.
--   - 채점    : /feedback/multi 는 personas 를 1~6명까지 받아 5인도 계약 안이다.
--
-- 아래 1번이 0이면 그대로 좁혀도 된다. 0이 아니면 2번으로 재시도 대상을 갈라 본다.


-- 1) 기술 외 면접관이 4명인 면접(= 총 5인)이 몇 건이나 남아 있는가.
SELECT count(*) AS oversized_interviews
FROM (
    SELECT ip.interview_id
    FROM interview_persona ip
    JOIN persona p ON p.persona_id = ip.persona_id
    GROUP BY ip.interview_id
    HAVING count(*) FILTER (WHERE p.role <> 'TECH') > 3
) oversized;


-- 2) 그 면접들을 재시도 대상 여부로 갈라 본다.
--    질문 준비가 아직 안 끝났거나(PENDING) 실패한(FAILED) 건, 그리고 면접이
--    진행 중인 건이 실제 위험군이다. 채점은 상한 밖이라 참고용으로만 센다.
WITH oversized AS (
    SELECT ip.interview_id,
           count(*) FILTER (WHERE p.role <> 'TECH') AS other_count
    FROM interview_persona ip
    JOIN persona p ON p.persona_id = ip.persona_id
    GROUP BY ip.interview_id
    HAVING count(*) FILTER (WHERE p.role <> 'TECH') > 3
),
latest_tailor AS (
    SELECT DISTINCT ON (qt.interview_id)
           qt.interview_id, qt.status, qt.chat_delivered
    FROM question_tailor qt
    ORDER BY qt.interview_id, qt.created_at DESC
),
latest_feedback AS (
    SELECT DISTINCT ON (f.interview_id)
           f.interview_id, f.status
    FROM feedback f
    ORDER BY f.interview_id, f.created_at DESC
)
SELECT o.interview_id,
       o.other_count,
       i.status                                   AS interview_status,
       coalesce(t.status, 'NOT_REQUESTED')        AS tailor_status,
       t.chat_delivered,
       coalesce(fb.status, 'NOT_REQUESTED')       AS feedback_status,
       -- 상한을 좁힌 뒤 준비를 다시 타면 분석 서버에서 422로 떨어지는 건.
       (i.status = 'IN_PROGRESS'
            AND coalesce(t.status, 'NOT_REQUESTED') <> 'SUCCEEDED') AS needs_handling
FROM oversized o
JOIN interview i ON i.interview_id = o.interview_id
LEFT JOIN latest_tailor t ON t.interview_id = o.interview_id
LEFT JOIN latest_feedback fb ON fb.interview_id = o.interview_id
ORDER BY needs_handling DESC, o.interview_id;


-- 3) 처리 대상 건수만 빠르게.
WITH oversized AS (
    SELECT ip.interview_id
    FROM interview_persona ip
    JOIN persona p ON p.persona_id = ip.persona_id
    GROUP BY ip.interview_id
    HAVING count(*) FILTER (WHERE p.role <> 'TECH') > 3
),
latest_tailor AS (
    SELECT DISTINCT ON (qt.interview_id) qt.interview_id, qt.status
    FROM question_tailor qt
    ORDER BY qt.interview_id, qt.created_at DESC
)
SELECT count(*) AS needs_handling
FROM oversized o
JOIN interview i ON i.interview_id = o.interview_id
LEFT JOIN latest_tailor t ON t.interview_id = o.interview_id
WHERE i.status = 'IN_PROGRESS'
  AND coalesce(t.status, 'NOT_REQUESTED') <> 'SUCCEEDED';
