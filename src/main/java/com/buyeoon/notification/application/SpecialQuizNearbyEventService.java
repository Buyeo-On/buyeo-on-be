package com.buyeoon.notification.application;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.mission.application.MissionQueryService;
import com.buyeoon.mission.application.MissionQueryService.SpecialQuizNearbyCheck;
import com.buyeoon.notification.NotificationCreationService;
import com.buyeoon.notification.api.InvalidNotificationRequestException;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationIdempotencyRequestRepository;
import com.buyeoon.notification.repository.NotificationRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
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
 * 스페셜 퀴즈 근접 알림 커맨드 서비스다. 클라이언트가 오늘 자신에게 노출된 스페셜 퀴즈만 지오펜스로 등록해두었다가 참여 반경
 * 진입을 알려오면, 서버가 노출 대상·참여 여부·거리를 재검증한 뒤 알림을 보낸다.
 */
@Service
public class SpecialQuizNearbyEventService {

	private static final String OPERATION = "NOTIFY_NEARBY_QUIZ";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final Duration COOLDOWN = Duration.ofHours(12);
	private static final int SUCCESS_STATUS = 200;

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final MissionQueryService missionQueryService;
	private final NotificationRepository notifications;
	private final NotificationCreationService notificationCreationService;
	private final NotificationIdempotencyRequestRepository idempotencyRequests;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	public SpecialQuizNearbyEventService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			MissionQueryService missionQueryService, NotificationRepository notifications,
			NotificationCreationService notificationCreationService,
			NotificationIdempotencyRequestRepository idempotencyRequests, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.missionQueryService = missionQueryService;
		this.notifications = notifications;
		this.notificationCreationService = notificationCreationService;
		this.idempotencyRequests = idempotencyRequests;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	public SpecialQuizNearbyEventView notify(UUID memberId, UUID missionId, String idempotencyKey,
			SpecialQuizNearbyEventCommand command) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = hash(missionId, command);
		return Objects.requireNonNull(
				transactions.execute(
						status -> notifyInTransaction(memberId, missionId, idempotencyKey, requestHash, command)),
				"스페셜 퀴즈 근접 알림 트랜잭션 결과가 없습니다.");
	}

	private SpecialQuizNearbyEventView notifyInTransaction(UUID memberId, UUID missionId, String idempotencyKey,
			String requestHash, SpecialQuizNearbyEventCommand command) {
		Instant now = clockTimestamp();

		IdempotencyRequestId idempotencyId = new IdempotencyRequestId(memberId, idempotencyKey);
		Optional<IdempotencyRequestEntity> existingRequest = idempotencyRequests.findById(idempotencyId);
		if (existingRequest.isPresent()) {
			IdempotencyRequestEntity request = existingRequest.get();
			if (!request.getExpiresAt().isAfter(now)) {
				idempotencyRequests.delete(request);
			} else {
				return replay(request, requestHash);
			}
		}

		boolean notificationSent = evaluateAndNotify(memberId, missionId, command, now);

		SpecialQuizNearbyEventView result = new SpecialQuizNearbyEventView(notificationSent);
		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, now.plus(RETENTION));
		idempotencyRequest.complete(SUCCESS_STATUS, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	/**
	 * 오늘 노출된 스페셜 퀴즈인지 → 이미 참여했는지 → 참여 반경 이내인지 → 쿨다운 순으로 재검증한 뒤에만 알림을 보낸다. 클라이언트가
	 * 보낸 missionId·좌표를 그대로 신뢰하지 않는다.
	 */
	private boolean evaluateAndNotify(UUID memberId, UUID missionId, SpecialQuizNearbyEventCommand command,
			Instant now) {
		SpecialQuizNearbyCheck check = missionQueryService.checkSpecialQuizNearby(memberId, command.tripId(),
				missionId, command.location().latitude(), command.location().longitude());
		if (!check.specialQuiz() || !check.exposedToday()) {
			return false;
		}
		if (check.alreadyParticipated()) {
			return false;
		}
		if (!check.withinParticipationRadius()) {
			return false;
		}
		if (notifications.existsByMemberIdAndTypeAndTargetIdAndOccurredAtAfter(memberId, NotificationType.NEARBY_QUIZ,
				missionId, now.minus(COOLDOWN))) {
			return false;
		}
		notificationCreationService.createNearbyQuiz(memberId, missionId);
		return true;
	}

	private SpecialQuizNearbyEventView replay(IdempotencyRequestEntity request, String requestHash) {
		if (!OPERATION.equals(request.getOperation()) || !requestHash.equals(request.getRequestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		if (!Integer.valueOf(SUCCESS_STATUS).equals(request.getResponseStatus()) || request.getResponseBody() == null) {
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
			throw new InvalidNotificationRequestException();
		}
	}

	private String hash(UUID missionId, SpecialQuizNearbyEventCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, missionId.toString());
			update(digest, command.tripId().toString());
			update(digest, Double.toHexString(command.location().latitude()));
			update(digest, Double.toHexString(command.location().longitude()));
			update(digest,
					command.location().accuracyMeters() == null
							? "null"
							: Double.toHexString(command.location().accuracyMeters()));
			update(digest, command.location().capturedAt().toInstant().toString());
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private String writeResponse(SpecialQuizNearbyEventView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("스페셜 퀴즈 근접 알림 응답을 저장할 수 없습니다.", exception);
		}
	}

	private SpecialQuizNearbyEventView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new SpecialQuizNearbyEventView(requiredBoolean(data, "notificationSent"));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 스페셜 퀴즈 근접 알림 응답을 읽을 수 없습니다.", exception);
		}
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 스페셜 퀴즈 근접 알림 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private boolean requiredBoolean(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isBoolean()) {
			throw new IllegalStateException("저장된 스페셜 퀴즈 근접 알림 응답 필드가 boolean이 아닙니다: " + field);
		}
		return value.booleanValue();
	}

	public record SpecialQuizNearbyEventCommand(UUID tripId, LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	public record SpecialQuizNearbyEventView(boolean notificationSent) {
	}
}
