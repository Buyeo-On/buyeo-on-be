UPDATE mission_participations
SET status = 'AVAILABLE'
WHERE status = 'LOCKED';

ALTER TABLE mission_participations
    ALTER COLUMN status DROP DEFAULT,
    DROP CONSTRAINT mission_participations_check;

ALTER TYPE mission_status RENAME TO mission_status_v1;

CREATE TYPE mission_status AS ENUM ('AVAILABLE', 'EXHAUSTED', 'COMPLETED');

ALTER TABLE mission_participations
    ALTER COLUMN status TYPE mission_status
    USING status::text::mission_status,
    ALTER COLUMN status SET DEFAULT 'AVAILABLE',
    ADD CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    );

DROP TYPE mission_status_v1;

UPDATE missions
SET max_attempts = NULL
WHERE type = 'PHOTO' AND max_attempts IS NOT NULL;

ALTER TABLE missions
    ADD CONSTRAINT missions_photo_max_attempts_ck
    CHECK (type <> 'PHOTO' OR max_attempts IS NULL);
