-- 면접 사전설정에서 고른 면접관의 음성 카탈로그 번호. 채팅 서버가 이 번호로 TTS 음성을 고른다.
-- 예전 페르소나에는 값이 없어 NULL을 허용한다. 채팅 서버는 NULL이면 기본 음성을 쓴다.
ALTER TABLE persona
    ADD COLUMN voice_index INTEGER;

ALTER TABLE persona
    ADD CONSTRAINT persona_voice_index_check
    CHECK (voice_index IS NULL OR voice_index BETWEEN 1 AND 5);
