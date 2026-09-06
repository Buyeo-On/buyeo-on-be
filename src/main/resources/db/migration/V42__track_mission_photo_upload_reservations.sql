CREATE TABLE mission_photo_upload_reservations (
    photo_id uuid PRIMARY KEY,
    member_id uuid REFERENCES members(id) ON DELETE SET NULL,
    trip_id uuid NOT NULL,
    mission_id uuid NOT NULL,
    object_key text NOT NULL UNIQUE CHECK (object_key LIKE 'private/%'),
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    file_size_bytes bigint NOT NULL CHECK (file_size_bytes > 0),
    created_at timestamptz NOT NULL,
    presigned_expires_at timestamptz NOT NULL,
    cleanup_due_at timestamptz NOT NULL,
    CHECK (presigned_expires_at > created_at),
    CHECK (cleanup_due_at = created_at + INTERVAL '24 hours')
);

CREATE INDEX mission_photo_upload_reservations_cleanup_idx
    ON mission_photo_upload_reservations (cleanup_due_at, photo_id);

CREATE INDEX mission_photo_upload_reservations_member_idx
    ON mission_photo_upload_reservations (member_id)
    WHERE member_id IS NOT NULL;
