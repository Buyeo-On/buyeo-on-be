package com.buyeoon.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.buyeoon.common.storage.PrivateImageObjectStore;
import com.buyeoon.member.application.WithdrawnMemberDataPurgeService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {"member.purge.initial-delay=PT24H", "member.purge.batch-size=1"})
@Testcontainers
class WithdrawnMemberDataPurgeIntegrationTests {

	private static final UUID PLACE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID PHOTO_MISSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000103");
	private static final String APPLICATION_USERNAME = "buyeoon_app";
	private static final String APPLICATION_PASSWORD = "application-test-password";

	@Container
	private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("buyeoon_test").withUsername("buyeoon_admin").withPassword("admin-test-password")
			.withInitScript("db/test-postgis-init.sql");

	@Autowired
	private WithdrawnMemberDataPurgeService purgeService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private PrivateImageObjectStore objectStore;

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_member_settings_delete ON member_settings");
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_member_settings_delete()");
		jdbcTemplate.update("DELETE FROM point_transactions");
		jdbcTemplate.update("DELETE FROM member_badges");
		jdbcTemplate.update("DELETE FROM mission_submissions");
		jdbcTemplate.update("DELETE FROM mission_photos");
		jdbcTemplate.update("DELETE FROM trips");
		jdbcTemplate.update("DELETE FROM notifications");
		jdbcTemplate.update("DELETE FROM idempotency_requests");
		jdbcTemplate.update("DELETE FROM saved_places");
		jdbcTemplate.update("DELETE FROM citizen_cards");
		jdbcTemplate.update("DELETE FROM member_profiles");
		jdbcTemplate.update("DELETE FROM term_consents");
		jdbcTemplate.update("DELETE FROM terms");
		jdbcTemplate.update("DELETE FROM push_tokens");
		jdbcTemplate.update("DELETE FROM auth_sessions");
		jdbcTemplate.update("DELETE FROM member_settings");
		jdbcTemplate.update("DELETE FROM social_accounts");
		jdbcTemplate.update("DELETE FROM members");
		jdbcTemplate.update("DELETE FROM badges");
		jdbcTemplate.update("DELETE FROM card_characters");
		jdbcTemplate.update("DELETE FROM card_themes");
		reset(objectStore);
	}

	@Test
	@DisplayName("기한이 지난 회원의 사진을 먼저 지우고 서비스 이용 데이터를 파기한다")
	void purgesDueMemberDataAndKeepsMinimalTombstone() {
		Fixture due = insertRichWithdrawnMember("private/missions/due-photo.webp", Instant.now().minusSeconds(1));
		UUID futureMemberId = insertMinimalWithdrawnMember(Instant.now().plus(1, ChronoUnit.DAYS));

		assertThat(purgeService.purgeDueMembers()).isEqualTo(1);

		verify(objectStore).delete(due.objectKey());
		assertPurged(due);
		assertThat(member(futureMemberId).get("purged_at")).isNull();
		assertThat(count("member_settings", "member_id", futureMemberId)).isEqualTo(1);
		assertThat(purgeService.purgeDueMembers()).isZero();
		verify(objectStore).delete(due.objectKey());
	}

	@Test
	@DisplayName("한 회원의 S3 삭제 실패는 DB를 유지하고 다른 회원 파기를 막지 않는다")
	void objectFailureIsRetriedAndIsolatedByMember() {
		Fixture failed = insertRichWithdrawnMember("private/missions/failed-photo.webp", Instant.now().minusSeconds(2));
		Fixture successful = insertRichWithdrawnMember("private/missions/success-photo.webp",
				Instant.now().minusSeconds(1));
		doAnswer(invocation -> {
			if (failed.objectKey().equals(invocation.getArgument(0))) {
				throw new IllegalStateException("forced object deletion failure");
			}
			return null;
		}).when(objectStore).delete(anyString());

		assertThat(purgeService.purgeDueMembers()).isEqualTo(1);
		assertThat(member(failed.memberId()).get("purged_at")).isNull();
		assertThat(count("member_settings", "member_id", failed.memberId())).isEqualTo(1);
		assertPurged(successful);

		reset(objectStore);
		assertThat(purgeService.purgeDueMembers()).isEqualTo(1);
		assertPurged(failed);
	}

	@Test
	@DisplayName("DB 파기 실패는 관계형 변경을 롤백하고 다음 실행에서 객체 삭제부터 재시도한다")
	void databaseFailureRollsBackAndRetries() {
		Fixture fixture = insertRichWithdrawnMember("private/missions/retry-photo.webp", Instant.now().minusSeconds(1));
		jdbcTemplate.execute("""
				CREATE FUNCTION fail_member_settings_delete() RETURNS trigger AS $$
				BEGIN
				    RAISE EXCEPTION 'forced relational purge failure';
				END;
				$$ LANGUAGE plpgsql
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER fail_member_settings_delete
				BEFORE DELETE ON member_settings
				FOR EACH ROW EXECUTE FUNCTION fail_member_settings_delete()
				""");

		assertThat(purgeService.purgeDueMembers()).isZero();
		assertThat(member(fixture.memberId()).get("purged_at")).isNull();
		assertThat(count("trips", "member_id", fixture.memberId())).isEqualTo(1);
		assertThat(count("member_settings", "member_id", fixture.memberId())).isEqualTo(1);

		jdbcTemplate.execute("DROP TRIGGER fail_member_settings_delete ON member_settings");
		assertThat(purgeService.purgeDueMembers()).isEqualTo(1);
		assertPurged(fixture);
		verify(objectStore, times(2)).delete(fixture.objectKey());
	}

	@Test
	@DisplayName("S3 객체가 없는 기한 도래 회원도 파기한다")
	void purgesDueMemberWithoutPhoto() {
		UUID memberId = insertMinimalWithdrawnMember(Instant.now().minusSeconds(1));

		assertThat(purgeService.purgeDueMembers()).isEqualTo(1);

		assertThat(member(memberId).get("purged_at")).isNotNull();
		assertThat(count("member_settings", "member_id", memberId)).isZero();
		verify(objectStore, times(0)).delete(anyString());
	}

	private Fixture insertRichWithdrawnMember(String objectKey, Instant purgeAfter) {
		UUID memberId = insertMinimalWithdrawnMember(purgeAfter);
		UUID sessionId = UUID.randomUUID();
		UUID characterId = UUID.randomUUID();
		UUID themeId = UUID.randomUUID();
		UUID termId = UUID.randomUUID();
		UUID tripId = UUID.randomUUID();
		UUID participationId = UUID.randomUUID();
		UUID photoId = UUID.randomUUID();
		UUID badgeId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO auth_sessions (id, member_id, refresh_token_hash, expires_at, revoked_at)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
				""", sessionId, memberId, UUID.randomUUID().toString());
		jdbcTemplate.update("INSERT INTO push_tokens (auth_session_id, registration_token) VALUES (?, ?)", sessionId,
				UUID.randomUUID().toString());
		jdbcTemplate.update("""
				INSERT INTO terms (id, type, version, required, title, content, effective_at)
				VALUES (?, 'SERVICE', ?, true, '약관', '본문', CURRENT_TIMESTAMP - INTERVAL '2 days')
				""", termId, UUID.randomUUID().toString());
		jdbcTemplate.update("INSERT INTO term_consents (member_id, term_id, agreed) VALUES (?, ?, true)", memberId,
				termId);
		jdbcTemplate.update("""
				INSERT INTO card_characters (id, name, image_key, sort_order)
				VALUES (?, ?, ?, ?)
				""", characterId, "캐릭터-" + memberId, "public/characters/" + memberId + ".webp",
				nextSortOrder("card_characters"));
		jdbcTemplate.update("""
				INSERT INTO card_themes (id, name, image_key, sort_order)
				VALUES (?, ?, ?, ?)
				""", themeId, "테마-" + memberId, "public/themes/" + memberId + ".webp", nextSortOrder("card_themes"));
		jdbcTemplate.update("""
				INSERT INTO member_profiles (member_id, display_name, character_id)
				VALUES (?, '탈퇴회원', ?)
				""", memberId, characterId);
		jdbcTemplate.update("INSERT INTO citizen_cards (member_id, theme_id, barcode_value) VALUES (?, ?, ?)", memberId,
				themeId, UUID.randomUUID().toString());
		jdbcTemplate.update("INSERT INTO saved_places (member_id, place_id) VALUES (?, ?)", memberId, PLACE_ID);
		jdbcTemplate.update("""
				INSERT INTO trips (id, member_id, status, started_at, ended_at, settled_at)
				VALUES (?, ?, 'SETTLED', CURRENT_TIMESTAMP - INTERVAL '3 hours',
				        CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 hour')
				""", tripId, memberId);
		jdbcTemplate.update("""
				INSERT INTO mission_participations (id, trip_id, mission_id, status, attempt_count, completed_at)
				VALUES (?, ?, ?, 'COMPLETED', 1, CURRENT_TIMESTAMP - INTERVAL '2 hours')
				""", participationId, tripId, PHOTO_MISSION_ID);
		jdbcTemplate.update("""
				INSERT INTO mission_photos
				    (id, member_id, trip_id, mission_id, object_key, content_type, file_size_bytes)
				VALUES (?, ?, ?, ?, ?, 'image/jpeg', 1024)
				""", photoId, memberId, tripId, PHOTO_MISSION_ID, objectKey);
		jdbcTemplate.update("""
				INSERT INTO mission_submissions (participation_id, type, photo_id, correct)
				VALUES (?, 'PHOTO', ?, true)
				""", participationId, photoId);
		jdbcTemplate.update("""
				INSERT INTO visit_records (trip_id, mission_id, place_id)
				VALUES (?, ?, ?)
				""", tripId, PHOTO_MISSION_ID, PLACE_ID);
		jdbcTemplate.update("""
				INSERT INTO point_transactions
				    (member_id, trip_id, participation_id, type, amount, description)
				VALUES (?, ?, ?, 'EARN', 100, '사진 미션')
				""", memberId, tripId, participationId);
		jdbcTemplate.update("""
				INSERT INTO point_settlements (trip_id, choice, settled_points, settled_at)
				VALUES (?, 'LEAVE_TO_BUYEO', 100, CURRENT_TIMESTAMP - INTERVAL '1 hour')
				""", tripId);
		jdbcTemplate.update("""
				INSERT INTO badges (id, category, name, description, condition_text)
				VALUES (?, 'RECORD', ?, '설명', '조건')
				""", badgeId, "배지-" + memberId);
		jdbcTemplate.update("INSERT INTO member_badges (member_id, badge_id, trip_id) VALUES (?, ?, ?)", memberId,
				badgeId, tripId);
		jdbcTemplate.update("""
				INSERT INTO notifications (member_id, type, title, body)
				VALUES (?, 'POINT', '포인트', '적립')
				""", memberId);
		jdbcTemplate.update("""
				INSERT INTO idempotency_requests
				    (member_id, idempotency_key, operation, request_hash, expires_at)
				VALUES (?, ?, 'test', 'hash', CURRENT_TIMESTAMP + INTERVAL '1 day')
				""", memberId, UUID.randomUUID().toString());
		return new Fixture(memberId, sessionId, tripId, participationId, photoId, objectKey);
	}

	private UUID insertMinimalWithdrawnMember(Instant purgeAfter) {
		UUID memberId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO members (id, status, withdrawn_at, purge_after)
				VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP - INTERVAL '30 days', ?)
				""", memberId, Timestamp.from(purgeAfter));
		jdbcTemplate.update("""
				INSERT INTO social_accounts (member_id, provider, provider_subject)
				VALUES (?, 'KAKAO', ?)
				""", memberId, UUID.randomUUID().toString());
		jdbcTemplate.update("INSERT INTO member_settings (member_id) VALUES (?)", memberId);
		return memberId;
	}

	private int nextSortOrder(String table) {
		return Objects.requireNonNull(
				jdbcTemplate.queryForObject("SELECT COALESCE(max(sort_order), 0) + 1 FROM " + table, Integer.class));
	}

	private void assertPurged(Fixture fixture) {
		Map<String, Object> member = member(fixture.memberId());
		assertThat(member.get("status").toString()).isEqualTo("WITHDRAWN");
		assertThat(member.get("purged_at")).isNotNull();
		assertThat(count("social_accounts", "member_id", fixture.memberId())).isEqualTo(1);
		for (String table : new String[]{"auth_sessions", "term_consents", "member_profiles", "citizen_cards",
				"member_settings", "saved_places", "trips", "mission_photos", "point_transactions", "member_badges",
				"notifications", "idempotency_requests"}) {
			assertThat(count(table, "member_id", fixture.memberId())).as(table).isZero();
		}
		assertThat(count("push_tokens", "auth_session_id", fixture.sessionId())).isZero();
		assertThat(count("mission_participations", "trip_id", fixture.tripId())).isZero();
		assertThat(count("mission_submissions", "participation_id", fixture.participationId())).isZero();
		assertThat(count("mission_submissions", "photo_id", fixture.photoId())).isZero();
		assertThat(count("visit_records", "trip_id", fixture.tripId())).isZero();
		assertThat(count("point_settlements", "trip_id", fixture.tripId())).isZero();
	}

	private Map<String, Object> member(UUID memberId) {
		return jdbcTemplate.queryForMap("SELECT * FROM members WHERE id = ?", memberId);
	}

	private long count(String table, String memberColumn, UUID memberId) {
		return Objects.requireNonNull(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE " + memberColumn + " = ?", Long.class, memberId));
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.username", () -> APPLICATION_USERNAME);
		registry.add("spring.datasource.password", () -> APPLICATION_PASSWORD);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}

	private record Fixture(UUID memberId, UUID sessionId, UUID tripId, UUID participationId, UUID photoId,
			String objectKey) {
	}
}
