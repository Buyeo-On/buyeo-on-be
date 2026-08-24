package com.buyeoon.member.application;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

/** 다른 도메인이 회원의 실제 발송 대상 등록 토큰을 조회할 때 사용하는 회원 도메인의 공개 seam이다. */
@Service
public class PushTargetQueryService {

	private final JdbcOperations jdbcOperations;

	public PushTargetQueryService(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	/** 활성 회원의 미폐기·미만료 인증 세션에 연결되고 알림 동의가 켜진 등록 토큰만 반환한다. */
	public List<String> findRegistrationTokens(UUID memberId) {
		return jdbcOperations.query("""
				SELECT push_token.registration_token
				FROM push_tokens push_token
				JOIN auth_sessions session ON session.id = push_token.auth_session_id
				JOIN members member ON member.id = session.member_id
				JOIN member_settings settings ON settings.member_id = member.id
				WHERE member.id = ?
				  AND member.status = 'ACTIVE'
				  AND session.revoked_at IS NULL
				  AND session.expires_at > CURRENT_TIMESTAMP
				  AND settings.nearby_quiz_notification_enabled = true
				""", (resultSet, rowNumber) -> resultSet.getString("registration_token"), memberId);
	}

	/** FCM이 {@code UNREGISTERED}로 응답한 등록 토큰을 삭제하는 회원 도메인의 공개 seam이다. */
	public void deleteRegistrationTokens(List<String> registrationTokens) {
		if (registrationTokens.isEmpty()) {
			return;
		}
		jdbcOperations.batchUpdate("DELETE FROM push_tokens WHERE registration_token = ?",
				registrationTokens.stream().map(token -> new Object[]{token}).toList());
	}
}
