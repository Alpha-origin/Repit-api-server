-- 종합 점수가 어떻게 나왔는지를 함께 둔다(채점 방식 axis-v1).
--
-- 분석 서버는 이제 점수를 LLM에게 직접 매기게 하지 않고, 문항마다 축 등급만 받아 서버에서 계산한다.
-- 그 과정을 사용자에게 "의도 충족 88점 × 35% ... 최종 71점"처럼 보여주려면 축별 점수와 가중치가 필요하다.
-- 축은 방식이 바뀌면 늘거나 이름이 바뀔 수 있어 컬럼으로 풀지 않고 받은 모양 그대로 jsonb에 둔다.
ALTER TABLE feedback ADD COLUMN scoring_version VARCHAR(32);
ALTER TABLE feedback ADD COLUMN score_breakdown JSONB;
ALTER TABLE feedback_persona ADD COLUMN score_breakdown JSONB;
ALTER TABLE feedback_item ADD COLUMN axis_scores JSONB;

COMMENT ON COLUMN feedback.scoring_version IS
    '점수를 매긴 채점 방식. 방식이 바뀌면 점수 분포가 달라져 버전이 다른 점수끼리 비교하면 안 된다. 비어 있으면 산출 근거가 오기 전의 결과다.';
COMMENT ON COLUMN feedback.score_breakdown IS
    '종합 점수의 산출 근거. {scoringVersion, axes:[{axis, score, weight}], consistencyScore}. 해당 없는 축은 score·weight가 null이다.';
COMMENT ON COLUMN feedback_persona.score_breakdown IS
    '면접관 점수의 산출 근거. feedback.score_breakdown과 같은 구조다.';
COMMENT ON COLUMN feedback_item.axis_scores IS
    '문항의 축별 점수(0..100). 해당 없는 축은 null이다.';
