ALTER TABLE analysis_data
    ADD COLUMN user_id BIGINT,
    ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT now();

CREATE INDEX analysis_data_user_id_created_at_idx
    ON analysis_data (user_id, created_at DESC);
