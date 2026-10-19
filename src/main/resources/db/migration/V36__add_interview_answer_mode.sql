ALTER TABLE interview ADD COLUMN answer_mode varchar(16);
ALTER TABLE interview ADD CONSTRAINT ck_interview_answer_mode
    CHECK (answer_mode IS NULL OR answer_mode IN ('TEXT', 'VOICE'));
