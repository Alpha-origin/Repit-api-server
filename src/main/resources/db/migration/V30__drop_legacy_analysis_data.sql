-- 옛 /generate 결과를 지운다. 면접 질문·사이클·자동 이전 모두 PROFILE만 보고, 분석 서버도 /generate를 지웠다.
--
-- 지운 행을 가리키는 외래키는 없다. 지난 면접의 질문·답변·피드백은 각자 테이블에 따로 남는다.
-- question_tailor.analysis_job_id가 지운 작업 id를 가리킬 수 있지만, 끝난 면접에서는 쓰지 않는 값이다.
DELETE FROM analysis_data WHERE result_type = 'LEGACY_GENERATE';

-- 종류를 빠뜨린 저장이 옛 종류로 들어가지 않게 기본값과 허용 값을 함께 고친다.
ALTER TABLE analysis_data ALTER COLUMN result_type SET DEFAULT 'PROFILE';
ALTER TABLE analysis_data DROP CONSTRAINT analysis_data_result_type_check;
ALTER TABLE analysis_data
    ADD CONSTRAINT analysis_data_result_type_check CHECK (result_type IN ('PROFILE'));

COMMENT ON COLUMN analysis_data.result_type IS
    'PROFILE = /profile 결과({ profile, projectSummary }). 옛 /generate 결과(LEGACY_GENERATE)는 V30에서 지웠다.';
