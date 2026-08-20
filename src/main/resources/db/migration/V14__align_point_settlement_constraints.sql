ALTER TABLE point_settlements
    DROP CONSTRAINT point_settlements_check;

UPDATE point_settlements
SET choice = 'NO_POINTS',
    expires_at = NULL,
    expired_at = NULL
WHERE settled_points = 0;

ALTER TABLE point_settlements
    ADD CONSTRAINT point_settlements_check CHECK (
        (
            choice = 'LEAVE_TO_BUYEO'
            AND settled_points > 0
            AND expires_at IS NULL
            AND expired_at IS NULL
        )
        OR (
            choice = 'CARRY_OVER'
            AND settled_points > 0
            AND expires_at = settled_at + INTERVAL '240 hours'
            AND (expired_at IS NULL OR expired_at >= expires_at)
        )
        OR (
            choice = 'NO_POINTS'
            AND settled_points = 0
            AND expires_at IS NULL
            AND expired_at IS NULL
        )
    );
