package com.buyeoon.point.application;

import com.buyeoon.member.entity.MemberStatus;
import com.buyeoon.point.repository.PointMemberRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이월 포인트 만료를 확정하는 point 도메인의 공개 seam이다. 활성 회원 행을 먼저 잠그고 만료 도래한
 * {@code CARRY_OVER} 정산을 {@code EXPIRE} 내역으로 확정한다. 호출자가 이미 트랜잭션 안에 있으면 그 트랜잭션에
 * 참여하고, 없으면 회원별로 새 트랜잭션을 시작한다.
 */
@Service
public final class PointExpirationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PointExpirationService.class);
	private static final String EXPIRE_DESCRIPTION = "이월 포인트 만료";
	private static final int BATCH_SIZE = 200;

	private final PointMemberRepository members;
	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public PointExpirationService(PointMemberRepository members, JdbcOperations jdbcOperations,
			PlatformTransactionManager transactionManager) {
		this.members = members;
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * 만료 도래 정산이 있는 모든 활성 회원을 순회하며 회원별 독립 트랜잭션으로 만료를 확정한다. 한 회원의 실패는 로그로 남기고 다른 회원
	 * 처리를 막지 않으며, 실패한 회원은 다음 실행에서 다시 시도한다. 확정한 EXPIRE 내역 수를 반환한다.
	 */
	public int expireAllDue() {
		int expiredSettlements = 0;
		Set<UUID> processedMemberIds = new HashSet<>();
		DueSettlementRef cursor = null;
		while (true) {
			List<DueSettlementRef> batch = dueSettlementRefsAfter(cursor);
			if (batch.isEmpty()) {
				return expiredSettlements;
			}
			for (DueSettlementRef ref : batch) {
				if (processedMemberIds.add(ref.memberId())) {
					expiredSettlements += expireMemberSafely(ref.memberId());
				}
			}
			cursor = batch.getLast();
		}
	}

	/**
	 * 지정한 회원의 만료 도래 {@code CARRY_OVER} 정산을 확정하고 처리한 건수를 반환한다. 회원이 활성 상태가 아니면 아무 것도
	 * 하지 않는다.
	 */
	public int expireDueSettlements(UUID memberId) {
		Integer expired = transactions.execute(status -> expireDueSettlementsInTransaction(memberId));
		return expired == null ? 0 : expired;
	}

	private int expireMemberSafely(UUID memberId) {
		try {
			return expireDueSettlements(memberId);
		} catch (RuntimeException exception) {
			LOGGER.warn("이월 포인트 만료 확정에 실패했습니다. memberId={}", memberId, exception);
			return 0;
		}
	}

	private int expireDueSettlementsInTransaction(UUID memberId) {
		if (members.findByIdAndStatus(memberId, MemberStatus.ACTIVE).isEmpty()) {
			return 0;
		}
		List<DueSettlement> due = lockDueSettlements(memberId);
		if (due.isEmpty()) {
			return 0;
		}
		Instant processedAt = currentDbTime();
		for (DueSettlement settlement : due) {
			confirmExpiration(memberId, settlement, processedAt);
		}
		return due.size();
	}

	private void confirmExpiration(UUID memberId, DueSettlement settlement, Instant processedAt) {
		jdbcOperations.update("""
				INSERT INTO point_transactions (member_id, trip_id, type, amount, description, occurred_at)
				VALUES (?, ?, 'EXPIRE', ?, ?, ?)
				""", memberId, settlement.tripId(), -settlement.settledPoints(), EXPIRE_DESCRIPTION,
				Timestamp.from(settlement.expiresAt()));
		int updated = jdbcOperations.update("""
				UPDATE point_settlements
				SET expired_at = ?
				WHERE id = ? AND expired_at IS NULL
				""", Timestamp.from(processedAt), settlement.id());
		if (updated != 1) {
			throw new IllegalStateException("이월 포인트 만료 확정에 실패했습니다. settlementId=" + settlement.id());
		}
	}

	private List<DueSettlement> lockDueSettlements(UUID memberId) {
		return jdbcOperations.query("""
				SELECT settlement.id, settlement.trip_id, settlement.settled_points, settlement.expires_at
				FROM point_settlements settlement
				JOIN trips trip ON trip.id = settlement.trip_id
				WHERE trip.member_id = ?
				  AND settlement.choice = 'CARRY_OVER'
				  AND settlement.expired_at IS NULL
				  AND settlement.expires_at <= CURRENT_TIMESTAMP
				ORDER BY settlement.expires_at, settlement.id
				FOR UPDATE OF settlement
				""", this::mapDueSettlement, memberId);
	}

	private List<DueSettlementRef> dueSettlementRefsAfter(DueSettlementRef cursor) {
		if (cursor == null) {
			return jdbcOperations.query("""
					SELECT settlement.id AS settlement_id, settlement.expires_at, trip.member_id
					FROM point_settlements settlement
					JOIN trips trip ON trip.id = settlement.trip_id
					WHERE settlement.choice = 'CARRY_OVER'
					  AND settlement.expired_at IS NULL
					  AND settlement.expires_at <= CURRENT_TIMESTAMP
					ORDER BY settlement.expires_at, settlement.id
					LIMIT ?
					""", this::mapDueSettlementRef, BATCH_SIZE);
		}
		return jdbcOperations.query("""
				SELECT settlement.id AS settlement_id, settlement.expires_at, trip.member_id
				FROM point_settlements settlement
				JOIN trips trip ON trip.id = settlement.trip_id
				WHERE settlement.choice = 'CARRY_OVER'
				  AND settlement.expired_at IS NULL
				  AND settlement.expires_at <= CURRENT_TIMESTAMP
				  AND (settlement.expires_at, settlement.id) > (?, ?)
				ORDER BY settlement.expires_at, settlement.id
				LIMIT ?
				""", this::mapDueSettlementRef, Timestamp.from(cursor.expiresAt()), cursor.settlementId(), BATCH_SIZE);
	}

	private Instant currentDbTime() {
		return Objects.requireNonNull(jdbcOperations.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class))
				.toInstant();
	}

	private DueSettlement mapDueSettlement(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DueSettlement(resultSet.getObject("id", UUID.class), resultSet.getObject("trip_id", UUID.class),
				resultSet.getLong("settled_points"), resultSet.getTimestamp("expires_at").toInstant());
	}

	private DueSettlementRef mapDueSettlementRef(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DueSettlementRef(resultSet.getObject("settlement_id", UUID.class),
				resultSet.getTimestamp("expires_at").toInstant(), resultSet.getObject("member_id", UUID.class));
	}

	private record DueSettlement(UUID id, UUID tripId, long settledPoints, Instant expiresAt) {
	}

	private record DueSettlementRef(UUID settlementId, Instant expiresAt, UUID memberId) {
	}
}
