package com.buyeoon.notification.application;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.notification.NotificationCreationService;
import com.buyeoon.notification.api.InvalidNotificationRequestException;
import com.buyeoon.notification.entity.NotificationType;
import com.buyeoon.notification.repository.NotificationIdempotencyRequestRepository;
import com.buyeoon.notification.repository.NotificationRepository;
import com.buyeoon.trip.TripQueryService;
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

/** UC-28 부여 이탈 알림 커맨드 서비스다. */
@Service
public class BuyeoExitEventService {

	private static final String OPERATION = "NOTIFY_BUYEO_EXIT";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final Duration COOLDOWN = Duration.ofHours(12);
	private static final int SUCCESS_STATUS = 200;

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final BuyeoBoundary boundary;
	private final TripQueryService tripQueryService;
	private final NotificationRepository notifications;
	private final NotificationCreationService notificationCreationService;
	private final NotificationIdempotencyRequestRepository idempotencyRequests;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	/** 이탈 알림에 필요한 경계·여행·알림·멱등성 의존성을 주입한다. */
	public BuyeoExitEventService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			BuyeoBoundary boundary, TripQueryService tripQueryService, NotificationRepository notifications,
			NotificationCreationService notificationCreationService,
			NotificationIdempotencyRequestRepository idempotencyRequests, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.boundary = boundary;
		this.tripQueryService = tripQueryService;
		this.notifications = notifications;
		this.notificationCreationService = notificationCreationService;
		this.idempotencyRequests = idempotencyRequests;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	/** 멱등성 키와 제출 위치로 이탈 알림 발송 여부를 결정한다. */
	public BuyeoExitEventView notify(UUID memberId, String idempotencyKey, BuyeoExitEventCommand command) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = hash(command);
		return Objects.requireNonNull(
				transactions.execute(status -> notifyInTransaction(memberId, idempotencyKey, requestHash, command)),
				"부여 이탈 알림 트랜잭션 결과가 없습니다.");
	}

	/** 같은 키의 완료된 요청은 재사용하고, 새 요청만 평가해 저장한다. */
	private BuyeoExitEventView notifyInTransaction(UUID memberId, String idempotencyKey, String requestHash,
			BuyeoExitEventCommand command) {
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

		boolean notificationSent = evaluateAndNotify(memberId, command, now);

		BuyeoExitEventView result = new BuyeoExitEventView(notificationSent);
		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, now.plus(RETENTION));
		idempotencyRequest.complete(SUCCESS_STATUS, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	/** UC-28의 검증 순서(경계 밖 재검증 → 진행 중 여행 확인 → 쿨다운)를 따른다. */
	private boolean evaluateAndNotify(UUID memberId, BuyeoExitEventCommand command, Instant now) {
		if (boundary.covers(command.location().latitude(), command.location().longitude())) {
			return false;
		}
		if (!tripQueryService.hasActiveTrip(memberId)) {
			return false;
		}
		if (notifications.existsByMemberIdAndTypeAndOccurredAtAfter(memberId, NotificationType.BUYEO_EXIT,
				now.minus(COOLDOWN))) {
			return false;
		}
		notificationCreationService.createBuyeoExit(memberId);
		return true;
	}

	/** 같은 작업·본문의 완료된 멱등성 응답만 재사용한다. */
	private BuyeoExitEventView replay(IdempotencyRequestEntity request, String requestHash) {
		if (!OPERATION.equals(request.getOperation()) || !requestHash.equals(request.getRequestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		if (!Integer.valueOf(SUCCESS_STATUS).equals(request.getResponseStatus()) || request.getResponseBody() == null) {
			throw new IllegalStateException("완료되지 않은 멱등성 요청이 남아 있습니다.");
		}
		return readResponse(request.getResponseBody());
	}

	/** DB 시계를 트랜잭션 기준 시각으로 사용한다. */
	private Instant clockTimestamp() {
		return Objects.requireNonNull(jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp.class))
				.toInstant();
	}

	/** OpenAPI Idempotency-Key 길이 제약을 적용한다. */
	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new InvalidNotificationRequestException();
		}
	}

	/** 위치 필드로 요청 본문 해시를 만든다. */
	private String hash(BuyeoExitEventCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

	/** 길이 접두 문자열을 다이제스트에 넣는다. */
	private void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	/** 최초 성공 응답을 멱등성 레코드에 저장한다. */
	private String writeResponse(BuyeoExitEventView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("부여 이탈 알림 응답을 저장할 수 없습니다.", exception);
		}
	}

	/** 저장된 멱등성 응답에서 발송 여부를 복원한다. */
	private BuyeoExitEventView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new BuyeoExitEventView(requiredBoolean(data, "notificationSent"));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 부여 이탈 알림 응답을 읽을 수 없습니다.", exception);
		}
	}

	/** 필수 JSON 필드를 읽는다. */
	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 부여 이탈 알림 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	/** boolean 필드를 읽는다. */
	private boolean requiredBoolean(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isBoolean()) {
			throw new IllegalStateException("저장된 부여 이탈 알림 응답 필드가 boolean이 아닙니다: " + field);
		}
		return value.booleanValue();
	}

	public record BuyeoExitEventCommand(LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	public record BuyeoExitEventView(boolean notificationSent) {
	}
}
