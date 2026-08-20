package com.buyeoon.point.application;

import com.buyeoon.badge.BadgeEvaluationService;
import com.buyeoon.badge.BadgeEvaluationService.AwardedBadgeResult;
import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.point.api.InvalidPointRequestException;
import com.buyeoon.point.entity.PointSettlementEntity;
import com.buyeoon.point.entity.PointTransactionType;
import com.buyeoon.point.entity.SettlementChoice;
import com.buyeoon.point.repository.PointIdempotencyRequestRepository;
import com.buyeoon.point.repository.PointSettlementQueryRepository;
import com.buyeoon.point.repository.PointTransactionRepository;
import com.buyeoon.trip.TripSettlementService;
import com.buyeoon.trip.entity.TripStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

/**
 * UC-24 여행 포인트 정산 확정 커맨드 서비스다. 해당 여행의 {@code EARN} 합계만 정산 대상으로 삼아
 * {@code LEAVE_TO_BUYEO}, {@code CARRY_OVER} 또는 {@code NO_POINTS}로 정확히 한 번 정산하고
 * 여행을 {@code SETTLED}로 전이한다. TripEndService·MissionSubmissionService의 멱등성/락 패턴을
 * 재사용한다.
 */
@Service
public class PointSettlementService {

	private static final String OPERATION = "SETTLE_TRIP_POINTS";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final String LEAVE_TO_BUYEO_DESCRIPTION = "여행 포인트 정산: 부여에 남기기";

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final TripSettlementService tripSettlementService;
	private final PointExpirationService pointExpirationService;
	private final PointTransactionRepository pointTransactions;
	private final PointSettlementQueryRepository pointSettlements;
	private final PointIdempotencyRequestRepository idempotencyRequests;
	private final BadgeEvaluationService badgeEvaluationService;
	private final PublicImageUrlService publicImageUrlService;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PointSettlementService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			TripSettlementService tripSettlementService, PointExpirationService pointExpirationService,
			PointTransactionRepository pointTransactions, PointSettlementQueryRepository pointSettlements,
			PointIdempotencyRequestRepository idempotencyRequests, BadgeEvaluationService badgeEvaluationService,
			PublicImageUrlService publicImageUrlService, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.tripSettlementService = tripSettlementService;
		this.pointExpirationService = pointExpirationService;
		this.pointTransactions = pointTransactions;
		this.pointSettlements = pointSettlements;
		this.idempotencyRequests = idempotencyRequests;
		this.badgeEvaluationService = badgeEvaluationService;
		this.publicImageUrlService = publicImageUrlService;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	public TripSettlementView settle(UUID memberId, UUID tripId, String idempotencyKey, SettlementChoice choice) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = requestHash(tripId, choice);
		TripSettlementResult result = transactions
				.execute(status -> settleInTransaction(memberId, tripId, idempotencyKey, requestHash, choice));
		return toView(Objects.requireNonNull(result, "여행 포인트 정산 트랜잭션 결과가 없습니다."));
	}

	private TripSettlementResult settleInTransaction(UUID memberId, UUID tripId, String idempotencyKey,
			String requestHash, SettlementChoice choice) {
		pointExpirationService.expireDueSettlementsForActiveMember(memberId);

		TripStatus tripStatus = tripSettlementService.lockOwnedTripStatus(memberId, tripId);
		long settleablePoints = pointTransactions.sumAmountByTripIdAndType(tripId, PointTransactionType.EARN);
		Instant settledAt = clockTimestamp();

		// 이미 정산이 확정돼 트립 상태가 더 이상 ENDED가 아니어도 같은 키의 replay는 성공해야 하므로 상태 검증보다 먼저
		// 멱등성 결과를 확인한다.
		IdempotencyRequestId idempotencyId = new IdempotencyRequestId(memberId, idempotencyKey);
		Optional<IdempotencyRequestEntity> existingRequest = idempotencyRequests.findById(idempotencyId);
		if (existingRequest.isPresent()) {
			IdempotencyRequestEntity request = existingRequest.get();
			if (!request.getExpiresAt().isAfter(settledAt)) {
				idempotencyRequests.delete(request);
			} else {
				return replay(request, requestHash);
			}
		}

		if (tripStatus != TripStatus.ENDED) {
			throw new InvalidStateTransitionException();
		}

		if (settleablePoints > 0 && choice == null) {
			throw new InvalidPointRequestException();
		}
		if (settleablePoints == 0 && choice != null) {
			throw new InvalidPointRequestException();
		}
		SettlementChoice finalChoice = settleablePoints == 0 ? SettlementChoice.NO_POINTS : choice;

		long balance = pointTransactions.sumAmountByMemberId(memberId);
		if (balance < settleablePoints) {
			throw new IllegalStateException("전체 잔액이 정산 대상 포인트보다 작습니다. tripId=" + tripId);
		}

		PointSettlementEntity settlement = PointSettlementEntity.create(tripId, finalChoice, settleablePoints,
				settledAt);
		pointSettlements.save(settlement);

		if (finalChoice == SettlementChoice.LEAVE_TO_BUYEO) {
			leaveToBuyeo(memberId, tripId, settleablePoints, settledAt);
		}

		tripSettlementService.settle(tripId, settledAt);

		List<AwardedBadgeResult> newlyAwardedBadges = awardDonationBadges(memberId, tripId, finalChoice);

		long remainingBalance = pointTransactions.sumAmountByMemberId(memberId);
		TripSettlementResult result = new TripSettlementResult(tripId, finalChoice, settleablePoints, remainingBalance,
				settlement.getExpiresAt(), settledAt, newlyAwardedBadges);

		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, settledAt.plus(RETENTION));
		idempotencyRequest.complete(200, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	/** 여행 정산과 같은 확정 시각을 점유 시각으로 남기기 위해 JPA의 자동 발생 시각 생성을 우회해 직접 삽입한다. */
	private void leaveToBuyeo(UUID memberId, UUID tripId, long settleablePoints, Instant settledAt) {
		jdbcOperations.update("""
				INSERT INTO point_transactions (member_id, trip_id, type, amount, description, occurred_at)
				VALUES (?, ?, 'LEAVE_TO_BUYEO', ?, ?, ?)
				""", memberId, tripId, -settleablePoints, LEAVE_TO_BUYEO_DESCRIPTION, Timestamp.from(settledAt));
	}

	/**
	 * 양수 포인트를 부여에 남기는 정산만 {@code POINT_DONATION_COUNT}를 판정한다(UC-14, ADR-003). badge
	 * Provider query가 방금 확정한 정산 row를 포함하도록 flush한 뒤 같은 transaction에서 판정한다.
	 */
	private List<AwardedBadgeResult> awardDonationBadges(UUID memberId, UUID tripId, SettlementChoice choice) {
		if (choice != SettlementChoice.LEAVE_TO_BUYEO) {
			return List.of();
		}
		pointSettlements.flush();
		return badgeEvaluationService.award(memberId, tripId, Set.of(BadgeMetric.POINT_DONATION_COUNT));
	}

	private TripSettlementResult replay(IdempotencyRequestEntity request, String requestHash) {
		if (!OPERATION.equals(request.getOperation()) || !requestHash.equals(request.getRequestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		if (!Integer.valueOf(200).equals(request.getResponseStatus()) || request.getResponseBody() == null) {
			throw new IllegalStateException("완료되지 않은 멱등성 요청이 남아 있습니다.");
		}
		return readResponse(request.getResponseBody());
	}

	private Instant clockTimestamp() {
		return Objects.requireNonNull(jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp.class))
				.toInstant();
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new InvalidPointRequestException();
		}
	}

	private String requestHash(UUID tripId, SettlementChoice choice) {
		return tripId + ":" + (choice == null ? "null" : choice.name());
	}

	private String writeResponse(TripSettlementResult result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("여행 포인트 정산 응답을 저장할 수 없습니다.", exception);
		}
	}

	private TripSettlementResult readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new TripSettlementResult(UUID.fromString(requiredText(data, "tripId")),
					SettlementChoice.valueOf(requiredText(data, "choice")), requiredLong(data, "settledPoints"),
					requiredLong(data, "remainingBalance"), nullableInstant(data, "expiresAt"),
					Instant.parse(requiredText(data, "settledAt")), readBadges(required(data, "newlyAwardedBadges")));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 여행 포인트 정산 응답을 읽을 수 없습니다.", exception);
		}
	}

	private List<AwardedBadgeResult> readBadges(JsonNode node) {
		if (!node.isArray()) {
			throw new IllegalStateException("저장된 여행 포인트 정산 응답의 배지 목록이 배열이 아닙니다.");
		}
		List<AwardedBadgeResult> badges = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			JsonNode badge = node.get(index);
			badges.add(new AwardedBadgeResult(UUID.fromString(requiredText(badge, "badgeId")),
					requiredText(badge, "name"), nullableText(badge, "imageKey"), requiredText(badge, "condition"),
					Instant.parse(requiredText(badge, "earnedAt"))));
		}
		return badges;
	}

	/** 새로 획득한 배지의 image key를 요청 시점마다 새로 서명한 Presigned URL로 바꿔 최종 응답을 만든다. */
	private TripSettlementView toView(TripSettlementResult result) {
		List<AwardedBadgeView> badges = result.newlyAwardedBadges().stream()
				.map(badge -> new AwardedBadgeView(badge.badgeId(), badge.name(),
						badge.imageKey() != null ? publicImageUrlService.create(badge.imageKey()) : null,
						badge.condition(), badge.earnedAt()))
				.toList();
		return new TripSettlementView(result.tripId(), result.choice(), result.settledPoints(),
				result.remainingBalance(), result.expiresAt(), result.settledAt(), badges);
	}

	private Instant nullableInstant(JsonNode data, String field) {
		JsonNode value = data.get(field);
		return value == null || value.isNull() ? null : Instant.parse(value.stringValue());
	}

	private String nullableText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.stringValue();
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 여행 포인트 정산 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 여행 포인트 정산 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}

	private long requiredLong(JsonNode node, String field) {
		return required(node, field).longValue();
	}

	/** 멱등성 레코드에 저장하는 semantic result다. 배지 image key는 만료되는 URL 대신 그대로 저장한다. */
	private record TripSettlementResult(UUID tripId, SettlementChoice choice, long settledPoints, long remainingBalance,
			Instant expiresAt, Instant settledAt, List<AwardedBadgeResult> newlyAwardedBadges) {
		private TripSettlementResult {
			newlyAwardedBadges = List.copyOf(newlyAwardedBadges);
		}
	}

	public record TripSettlementView(UUID tripId, SettlementChoice choice, long settledPoints, long remainingBalance,
			Instant expiresAt, Instant settledAt, List<AwardedBadgeView> newlyAwardedBadges) {
		public TripSettlementView {
			newlyAwardedBadges = List.copyOf(newlyAwardedBadges);
		}
	}

	/** 요청마다 새로 서명한 10분 유효 Presigned URL을 담은 응답용 배지 뷰다. */
	public record AwardedBadgeView(UUID badgeId, String name, String imageUrl, String condition, Instant earnedAt) {
	}
}
