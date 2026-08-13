ALTER TABLE members
    ADD COLUMN purged_at timestamptz;

ALTER TABLE members
    DROP CONSTRAINT members_check;

ALTER TABLE members
    ADD CONSTRAINT members_lifecycle_ck CHECK (
        (status = 'ACTIVE' AND withdrawn_at IS NULL AND purge_after IS NULL AND purged_at IS NULL)
        OR (
            status = 'WITHDRAWN'
            AND withdrawn_at IS NOT NULL
            AND purge_after IS NOT NULL
            AND (purged_at IS NULL OR purged_at >= withdrawn_at)
        )
    );

CREATE INDEX members_due_purge_idx
    ON members (purge_after, id)
    WHERE status = 'WITHDRAWN' AND purged_at IS NULL;

ALTER TABLE mission_photos
    ADD CONSTRAINT mission_photos_object_key_prefix_ck
    CHECK (object_key LIKE 'private/%');
