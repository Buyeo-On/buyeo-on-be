-- 0포인트 정산도 LEAVE_TO_BUYEO/CARRY_OVER 선택을 저장할 수 있도록 완화한다.
-- 정산 대상 포인트가 0원이어도 UX상 정산 확인 흐름(기부/이월 선택)을 그대로 보여주기로 했다.
ALTER TABLE point_settlements DROP CONSTRAINT point_settlements_check;

ALTER TABLE point_settlements ADD CONSTRAINT point_settlements_check CHECK (
	choice = 'LEAVE_TO_BUYEO'::settlement_choice AND settled_points >= 0 AND expires_at IS NULL AND expired_at IS NULL
	OR choice = 'CARRY_OVER'::settlement_choice AND settled_points >= 0
		AND expires_at = (settled_at + '240:00:00'::interval) AND (expired_at IS NULL OR expired_at >= expires_at)
	OR choice = 'NO_POINTS'::settlement_choice AND settled_points = 0 AND expires_at IS NULL AND expired_at IS NULL
);
