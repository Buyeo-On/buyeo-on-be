package com.buyeoon.mission.application;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.common.storage.MissionPhotoUploadPresigner;
import com.buyeoon.common.storage.MissionPhotoUploadPresigner.MissionPhotoUploadTarget;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionIdempotencyRequestRepository;
import com.buyeoon.mission.repository.MissionQueryRepository;
import com.buyeoon.trip.TripQueryService;
import com.buyeoon.trip.entity.TripStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

/** UC-09 사진 인증 업로드용 Presigned URL 발급 커맨드 서비스다. */
@Service
public class MissionPhotoUploadUrlService {

	private static final String OPERATION = "CREATE_MISSION_PHOTO_UPLOAD_URL";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final int SUCCESS_STATUS = 201;

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final TripQueryService tripQueryService;
	private final MissionQueryRepository missionQueryRepository;
	private final MissionIdempotencyRequestRepository idempotencyRequests;
	private final MissionPhotoUploadPresigner presigner;
	private final long maxFileSizeBytes;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	public MissionPhotoUploadUrlService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			TripQueryService tripQueryService, MissionQueryRepository missionQueryRepository,
			MissionIdempotencyRequestRepository idempotencyRequests, MissionPhotoUploadPresigner presigner,
			@Value("${storage.images.max-upload-bytes:10485760}") long maxFileSizeBytes, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.tripQueryService = tripQueryService;
		this.missionQueryRepository = missionQueryRepository;
		this.idempotencyRequests = idempotencyRequests;
		this.presigner = presigner;
		this.maxFileSizeBytes = maxFileSizeBytes;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	public MissionPhotoUploadUrlView createUploadUrl(UUID memberId, String idempotencyKey,
			MissionPhotoUploadUrlCommand command) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = hash(command);
		return Objects.requireNonNull(
				transactions.execute(status -> createInTransaction(memberId, idempotencyKey, requestHash, command)),
				"사진 업로드 URL 발급 트랜잭션 결과가 없습니다.");
	}

	private MissionPhotoUploadUrlView createInTransaction(UUID memberId, String idempotencyKey, String requestHash,
			MissionPhotoUploadUrlCommand command) {
		TripStatus tripStatus = tripQueryService.findOwnedTripStatus(memberId, command.tripId())
				.orElseThrow(TripNotFoundException::new);
		if (tripStatus != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		MissionEntity mission = missionQueryRepository.findById(command.missionId())
				.orElseThrow(MissionNotFoundException::new);
		if (mission.getType() != MissionType.PHOTO) {
			throw new InvalidMissionSubmissionException();
		}

		Instant occurredAt = clockTimestamp();
		IdempotencyRequestId idempotencyId = new IdempotencyRequestId(memberId, idempotencyKey);
		Optional<IdempotencyRequestEntity> existingRequest = idempotencyRequests.findById(idempotencyId);
		if (existingRequest.isPresent()) {
			IdempotencyRequestEntity request = existingRequest.get();
			if (!request.getExpiresAt().isAfter(occurredAt)) {
				idempotencyRequests.delete(request);
			} else {
				return replay(request, requestHash);
			}
		}

		if (command.fileSizeBytes() > maxFileSizeBytes) {
			throw new MissionPhotoTooLargeException();
		}

		UUID photoId = UUID.randomUUID();
		String objectKey = MissionPhotoObjectKeys.key(command.tripId(), command.missionId(), photoId);
		MissionPhotoUploadTarget target = presigner.presign(objectKey, memberId, command.contentType(),
				command.fileSizeBytes());

		MissionPhotoUploadUrlView result = new MissionPhotoUploadUrlView(photoId, target.uploadUrl(), "PUT",
				target.headers(), 200, target.expiresAt());

		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, occurredAt.plus(RETENTION));
		idempotencyRequest.complete(SUCCESS_STATUS, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	private MissionPhotoUploadUrlView replay(IdempotencyRequestEntity request, String requestHash) {
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
			throw new InvalidMissionSubmissionException();
		}
	}

	private String hash(MissionPhotoUploadUrlCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, command.tripId().toString());
			update(digest, command.missionId().toString());
			update(digest, command.fileName());
			update(digest, command.contentType());
			update(digest, Long.toString(command.fileSizeBytes()));
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

	private String writeResponse(MissionPhotoUploadUrlView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("사진 업로드 URL 발급 응답을 저장할 수 없습니다.", exception);
		}
	}

	private MissionPhotoUploadUrlView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			Map<String, String> headers = new LinkedHashMap<>();
			required(data, "headers").properties()
					.forEach(property -> headers.put(property.getKey(), property.getValue().stringValue()));
			return new MissionPhotoUploadUrlView(UUID.fromString(requiredText(data, "photoId")),
					requiredText(data, "uploadUrl"), requiredText(data, "method"), headers,
					requiredInt(data, "successStatus"), Instant.parse(requiredText(data, "expiresAt")));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 사진 업로드 URL 발급 응답을 읽을 수 없습니다.", exception);
		}
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 사진 업로드 URL 발급 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 사진 업로드 URL 발급 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}

	private int requiredInt(JsonNode node, String field) {
		return required(node, field).intValue();
	}

	public record MissionPhotoUploadUrlCommand(UUID tripId, UUID missionId, String fileName, String contentType,
			long fileSizeBytes) {
	}

	public record MissionPhotoUploadUrlView(UUID photoId, String uploadUrl, String method, Map<String, String> headers,
			int successStatus, Instant expiresAt) {

		public MissionPhotoUploadUrlView {
			headers = Map.copyOf(headers);
		}
	}
}
