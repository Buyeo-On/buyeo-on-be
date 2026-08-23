package com.buyeoon.member.application;

import com.buyeoon.common.storage.PrivateImageObjectStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class WithdrawnMemberDataPurgeService {

	private static final Logger LOGGER = LoggerFactory.getLogger(WithdrawnMemberDataPurgeService.class);

	private final JdbcOperations jdbcOperations;
	private final Consumer<String> deleteObject;
	private final TransactionTemplate transactions;
	private final int batchSize;

	public WithdrawnMemberDataPurgeService(JdbcOperations jdbcOperations, PrivateImageObjectStore objectStore,
			PlatformTransactionManager transactionManager, @Value("${member.purge.batch-size:100}") int batchSize) {
		if (batchSize < 1 || batchSize > 1_000) {
			throw new IllegalArgumentException("회원 정보 파기 배치 크기는 1~1000이어야 합니다.");
		}
		this.jdbcOperations = jdbcOperations;
		this.deleteObject = objectStore::delete;
		this.transactions = new TransactionTemplate(transactionManager);
		this.batchSize = batchSize;
	}

	public int purgeDueMembers() {
		int purged = 0;
		DueMember cursor = null;
		while (true) {
			List<DueMember> dueMembers = dueMembersAfter(cursor);
			if (dueMembers.isEmpty()) {
				return purged;
			}
			for (DueMember dueMember : dueMembers) {
				try {
					if (purge(dueMember.memberId())) {
						purged++;
					}
				} catch (RuntimeException exception) {
					LOGGER.warn("탈퇴 회원 정보 파기에 실패했습니다. memberId={}", dueMember.memberId(), exception);
				}
			}
			cursor = dueMembers.getLast();
		}
	}

	private List<DueMember> dueMembersAfter(DueMember cursor) {
		if (cursor == null) {
			return jdbcOperations.query("""
					SELECT id, purge_after
					FROM members
					WHERE status = 'WITHDRAWN'
					  AND purge_after <= CURRENT_TIMESTAMP
					  AND purged_at IS NULL
					ORDER BY purge_after, id
					LIMIT ?
					""", this::mapDueMember, batchSize);
		}
		return jdbcOperations.query("""
				SELECT id, purge_after
				FROM members
				WHERE status = 'WITHDRAWN'
				  AND purge_after <= CURRENT_TIMESTAMP
				  AND purged_at IS NULL
				  AND (purge_after, id) > (?, ?)
				ORDER BY purge_after, id
				LIMIT ?
				""", this::mapDueMember, Timestamp.from(cursor.purgeAfter()), cursor.memberId(), batchSize);
	}

	private DueMember mapDueMember(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DueMember(resultSet.getObject("id", UUID.class), resultSet.getTimestamp("purge_after").toInstant());
	}

	private boolean purge(UUID memberId) {
		for (String objectKey : photoObjectKeys(memberId)) {
			deleteObject.accept(objectKey);
		}
		return Boolean.TRUE.equals(transactions.execute(status -> purgeRelationalData(memberId)));
	}

	private List<String> photoObjectKeys(UUID memberId) {
		return jdbcOperations.query("""
				SELECT object_key
				FROM mission_photos
				WHERE member_id = ?
				ORDER BY id
				""", (resultSet, rowNumber) -> resultSet.getString("object_key"), memberId);
	}

	private boolean purgeRelationalData(UUID memberId) {
		if (!lockDueMember(memberId)) {
			return false;
		}
		jdbcOperations.update("DELETE FROM idempotency_requests WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM notifications WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM member_badges WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM point_transactions WHERE member_id = ?", memberId);
		jdbcOperations.update("""
				DELETE FROM mission_submissions
				WHERE participation_id IN (
				    SELECT participation.id
				    FROM mission_participations participation
				    JOIN trips trip ON trip.id = participation.trip_id
				    WHERE trip.member_id = ?
				)
				OR photo_id IN (
				    SELECT id FROM mission_photos WHERE member_id = ?
				)
				""", memberId, memberId);
		jdbcOperations.update("DELETE FROM mission_photos WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM trips WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM saved_places WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM citizen_cards WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM member_profiles WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM term_consents WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM member_settings WHERE member_id = ?", memberId);
		jdbcOperations.update("DELETE FROM auth_sessions WHERE member_id = ?", memberId);
		int updated = jdbcOperations.update("DELETE FROM members WHERE id = ? AND status = 'WITHDRAWN'", memberId);
		if (updated != 1) {
			throw new IllegalStateException("탈퇴 회원 정보를 완전히 파기할 수 없습니다.");
		}
		return true;
	}

	private boolean lockDueMember(UUID memberId) {
		return !jdbcOperations.query("""
				SELECT id
				FROM members
				WHERE id = ?
				  AND status = 'WITHDRAWN'
				  AND purge_after <= CURRENT_TIMESTAMP
				  AND purged_at IS NULL
				FOR UPDATE
				""", (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), memberId).isEmpty();
	}

	private record DueMember(UUID memberId, Instant purgeAfter) {
	}
}
