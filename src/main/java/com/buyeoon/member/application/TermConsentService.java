package com.buyeoon.member.application;

import com.buyeoon.member.api.InvalidTermConsentRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TermConsentService {

	private static final String OPERATION = "UPDATE_TERM_CONSENTS";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;

	public TermConsentService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public TermConsentResult update(UUID memberId, String idempotencyKey, List<ConsentDecision> decisions) {
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new InvalidTermConsentRequestException();
		}
		String requestHash = hash(decisions);
		return Objects.requireNonNull(
				transactions.execute(status -> updateInTransaction(memberId, idempotencyKey, requestHash, decisions)),
				"약관 동의 트랜잭션 결과가 없습니다.");
	}

	private TermConsentResult updateInTransaction(UUID memberId, String idempotencyKey, String requestHash,
			List<ConsentDecision> decisions) {
		lockMember(memberId);
		Instant agreedAt = Objects
				.requireNonNull(jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant();
		IdempotencyState existingRequest = findIdempotencyRequest(memberId, idempotencyKey);
		if (existingRequest != null) {
			if (!existingRequest.expiresAt().isAfter(agreedAt)) {
				jdbcOperations.update("""
						DELETE FROM idempotency_requests
						WHERE member_id = ? AND idempotency_key = ?
						""", memberId, idempotencyKey);
			} else {
				if (!OPERATION.equals(existingRequest.operation())
						|| !requestHash.equals(existingRequest.requestHash())) {
					throw new IdempotencyKeyReusedException();
				}
				if (!Integer.valueOf(200).equals(existingRequest.responseStatus())) {
					throw new IllegalStateException("완료되지 않은 멱등성 요청이 남아 있습니다.");
				}
				return new TermConsentResult(true, existingRequest.createdAt().atZone(ASIA_SEOUL));
			}
		}
		List<CurrentTerm> currentTerms = getCurrentTerms();
		validate(decisions, currentTerms);

		for (ConsentDecision decision : decisions) {
			jdbcOperations.update("""
					INSERT INTO term_consents (member_id, term_id, agreed, agreed_at)
					VALUES (?, ?, ?, ?)
					ON CONFLICT (member_id, term_id) DO UPDATE
					SET agreed = EXCLUDED.agreed, agreed_at = EXCLUDED.agreed_at
					""", memberId, decision.termId(), decision.agreed(), Timestamp.from(agreedAt));
		}

		ZonedDateTime responseTime = agreedAt.atZone(ASIA_SEOUL);
		String responseBody = "{\"success\":true,\"data\":{\"requiredTermsAgreed\":true,\"agreedAt\":\""
				+ responseTime.toOffsetDateTime() + "\"}}";
		jdbcOperations.update("""
				INSERT INTO idempotency_requests
				    (member_id, idempotency_key, operation, request_hash, response_status, response_body,
				     created_at, expires_at)
				VALUES (?, ?, ?, ?, 200, ?::jsonb, ?, ?)
				""", memberId, idempotencyKey, OPERATION, requestHash, responseBody, Timestamp.from(agreedAt),
				Timestamp.from(agreedAt.plus(RETENTION)));
		return new TermConsentResult(true, responseTime);
	}

	private void lockMember(UUID memberId) {
		jdbcOperations.queryForObject("SELECT id FROM members WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
				UUID.class, memberId);
	}

	private IdempotencyState findIdempotencyRequest(UUID memberId, String idempotencyKey) {
		return jdbcOperations.query("""
				SELECT operation, request_hash, response_status, created_at, expires_at
				FROM idempotency_requests
				WHERE member_id = ? AND idempotency_key = ?
				FOR UPDATE
				""", this::mapIdempotencyState, memberId, idempotencyKey).stream().findFirst().orElse(null);
	}

	private IdempotencyState mapIdempotencyState(ResultSet resultSet, int rowNumber) throws SQLException {
		return new IdempotencyState(resultSet.getString("operation"), resultSet.getString("request_hash"),
				resultSet.getObject("response_status", Integer.class), resultSet.getTimestamp("created_at").toInstant(),
				resultSet.getTimestamp("expires_at").toInstant());
	}

	private List<CurrentTerm> getCurrentTerms() {
		return jdbcOperations.query("""
				SELECT DISTINCT ON (term.type) term.id, term.version, term.required
				FROM terms term
				WHERE term.effective_at <= clock_timestamp()
				ORDER BY term.type, term.effective_at DESC
				""", this::mapCurrentTerm);
	}

	private CurrentTerm mapCurrentTerm(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CurrentTerm(resultSet.getObject("id", UUID.class), resultSet.getString("version"),
				resultSet.getBoolean("required"));
	}

	private void validate(List<ConsentDecision> decisions, List<CurrentTerm> currentTerms) {
		Set<UUID> decisionIds = new HashSet<>();
		if (decisions.size() != currentTerms.size()
				|| decisions.stream().anyMatch(decision -> !decisionIds.add(decision.termId()))) {
			throw new InvalidTermConsentRequestException();
		}
		for (CurrentTerm term : currentTerms) {
			ConsentDecision decision = decisions.stream().filter(item -> item.termId().equals(term.termId()))
					.findFirst().orElseThrow(TermVersionOutdatedException::new);
			if (!term.version().equals(decision.version())) {
				throw new TermVersionOutdatedException();
			}
			if (term.required() && !decision.agreed()) {
				throw new InvalidTermConsentRequestException();
			}
		}
	}

	private String hash(List<ConsentDecision> decisions) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			decisions.stream().sorted(Comparator.comparing(ConsentDecision::termId)).forEach(decision -> {
				byte[] version = decision.version().getBytes(StandardCharsets.UTF_8);
				digest.update(decision.termId().toString().getBytes(StandardCharsets.US_ASCII));
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(version.length).array());
				digest.update(version);
				digest.update((byte) (decision.agreed() ? 1 : 0));
			});
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	public record ConsentDecision(UUID termId, String version, boolean agreed) {
	}

	public record TermConsentResult(boolean requiredTermsAgreed, ZonedDateTime agreedAt) {
	}

	private record CurrentTerm(UUID termId, String version, boolean required) {
	}

	private record IdempotencyState(String operation, String requestHash, Integer responseStatus, Instant createdAt,
			Instant expiresAt) {
	}
}
