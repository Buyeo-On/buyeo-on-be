ALTER TABLE point_settlements
    ADD COLUMN expired_at timestamptz; -- 이월 포인트 만료 처리를 실제로 확정한 시각

ALTER TABLE point_settlements
    DROP CONSTRAINT point_settlements_check;

ALTER TABLE point_settlements
    ADD CONSTRAINT point_settlements_check CHECK (
        (choice = 'LEAVE_TO_BUYEO' AND expires_at IS NULL AND expired_at IS NULL)
        OR (
            choice = 'CARRY_OVER'
            AND expires_at = settled_at + INTERVAL '240 hours'
            AND (expired_at IS NULL OR expired_at >= expires_at)
        )
    );

CREATE INDEX point_settlements_due_expiration_idx
    ON point_settlements (expires_at, id)
    WHERE choice = 'CARRY_OVER' AND expired_at IS NULL;
