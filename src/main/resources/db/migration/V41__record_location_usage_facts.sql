-- 위치정보법상 이용 사실 확인자료다. 원시 좌표와 정확도는 저장하지 않는다.
CREATE TABLE location_usage_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    purpose varchar(64) NOT NULL,
    used_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > used_at)
);

CREATE INDEX location_usage_records_expiry_idx
    ON location_usage_records (expires_at, id);
CREATE INDEX location_usage_records_member_idx
    ON location_usage_records (member_id, used_at DESC);
