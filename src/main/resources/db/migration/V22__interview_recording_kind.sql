-- 면접 파일을 두 종류로 나눈다.
--
-- 웹은 질문마다 그 답변의 음성을 올리고, 면접을 멈출 때 면접 화면을 처음부터 녹화한 영상 하나를 올린다.
-- 앞엣것은 질문에 붙고 뒤엣것은 면접 전체에 붙어서, 어느 쪽인지 모르면 질문과 이을 수 없다.
ALTER TABLE interview_recording ADD COLUMN kind VARCHAR(20);
ALTER TABLE interview_recording ADD COLUMN content_type VARCHAR(100);

-- 지금까지 올라온 것은 답변마다 끊어 녹화한 MP4다. 질문 번호가 없는 행은 어느 답변인지 알 수 없어
-- 채점에서 이미 빠지고 있었으므로, 면접 전체 영상 쪽으로 둔다.
UPDATE interview_recording
SET kind = CASE WHEN chat_question_id IS NULL THEN 'FULL_INTERVIEW' ELSE 'ANSWER' END,
    content_type = 'video/mp4';

ALTER TABLE interview_recording ALTER COLUMN kind SET NOT NULL;
ALTER TABLE interview_recording ALTER COLUMN content_type SET NOT NULL;

-- 답변 파일은 어느 질문의 것인지 없으면 쓸 수 없고, 면접 전체 영상은 질문 하나에 매이지 않는다.
ALTER TABLE interview_recording ADD CONSTRAINT interview_recording_kind_check CHECK (
    (kind = 'ANSWER' AND chat_question_id IS NOT NULL)
    OR (kind = 'FULL_INTERVIEW' AND chat_question_id IS NULL)
);

COMMENT ON COLUMN interview_recording.kind IS
    'ANSWER = 질문 하나의 답변 파일(웹이 답변을 마칠 때마다 올린다), FULL_INTERVIEW = 면접 화면을 처음부터 끝까지 녹화한 영상(면접을 멈출 때 한 번 올린다).';
COMMENT ON COLUMN interview_recording.content_type IS
    '파일 앞 바이트로 확인한 실제 형식. 분석 서버가 내려받기 전에 무엇인지 알 수 있게 그대로 넘긴다.';
