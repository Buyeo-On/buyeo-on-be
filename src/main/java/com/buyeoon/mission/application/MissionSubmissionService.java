package com.buyeoon.mission.application;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.mission.entity.MissionChoiceEntity;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionChoiceRepository;
import com.buyeoon.mission.repository.MissionIdempotencyRequestRepository;
import com.buyeoon.mission.repository.MissionParticipationRepository;
import com.buyeoon.mission.repository.MissionPlaceDistanceProjection;
import com.buyeoon.mission.repository.MissionQueryRepository;
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.point.PointRewardService;
import com.buyeoon.trip.TripQueryService;
import com.buyeoon.trip.VisitRecordService;
import com.buyeoon.trip.VisitRecordService.VisitRecordResult;
import com.buyeoon.trip.entity.TripStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

/** UC-09 객관식·OX 미션 답안 제출 커맨드 서비스다. */
@Service
public class MissionSubmissionService {

	private static final String OPERATION = "SUBMIT_MISSION";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final int PARTICIPATION_RADIUS_METERS = 100;

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final TripQueryService tripQueryService;
	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;
	private final MissionParticipationRepository missionParticipations;
	private final MissionSubmissionRepository missionSubmissions;
	private final MissionIdempotencyRequestRepository idempotencyRequests;
	private final VisitRecordService visitRecordService;
	private final PointRewardService pointRewardService;
	private final ApplicationEventPublisher events;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionSubmissionService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			TripQueryService tripQueryService, MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, MissionParticipationRepository missionParticipations,
			MissionSubmissionRepository missionSubmissions, MissionIdempotencyRequestRepository idempotencyRequests,
			VisitRecordService visitRecordService, PointRewardService pointRewardService,
			ApplicationEventPublisher events, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.tripQueryService = tripQueryService;
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
		this.missionParticipations = missionParticipations;
		this.missionSubmissions = missionSubmissions;
		this.idempotencyRequests = idempotencyRequests;
		this.visitRecordService = visitRecordService;
		this.pointRewardService = pointRewardService;
		this.events = events;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	public MissionSubmissionView submit(UUID memberId, UUID missionId, String idempotencyKey,
			MissionSubmissionCommand command) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = hash(missionId, command);
		return Objects.requireNonNull(
				transactions.execute(
						status -> submitInTransaction(memberId, missionId, idempotencyKey, requestHash, command)),
				"미션 제출 트랜잭션 결과가 없습니다.");
	}

	private MissionSubmissionView submitInTransaction(UUID memberId, UUID missionId, String idempotencyKey,
			String requestHash, MissionSubmissionCommand command) {
		TripStatus tripStatus = tripQueryService.findOwnedTripStatus(memberId, command.tripId())
				.orElseThrow(TripNotFoundException::new);
		if (tripStatus != TripStatus.IN_PROGRESS) {
			throw new TripNotInProgressException();
		}

		MissionPlaceDistanceProjection detail = missionQueryRepository
				.findWithDistance(missionId, command.location().latitude(), command.location().longitude())
				.orElseThrow(MissionNotFoundException::new);
		MissionEntity mission = detail.mission();
		if (mission.getType() != command.type()) {
			throw new InvalidMissionSubmissionException();
		}

		missionParticipations.insertIfAbsent(command.tripId(), missionId);
		MissionParticipationEntity participation = missionParticipations
				.lockByTripIdAndMissionId(command.tripId(), missionId)
				.orElseThrow(() -> new IllegalStateException("미션 참여 행을 잠글 수 없습니다."));

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

		if (participation.getStatus() != MissionStatus.AVAILABLE) {
			throw new InvalidStateTransitionException();
		}
		if (detail.distanceMeters() > PARTICIPATION_RADIUS_METERS) {
			throw new OutsideParticipationRadiusException();
		}

		boolean correct = determineCorrectness(command, mission, missionId);
		participation.recordAttempt(correct, mission.getMaxAttempts(), occurredAt);
		missionSubmissions.save(toSubmissionEntity(participation.getId(), command, correct));

		int rewardPoints = 0;
		long pointBalance = pointRewardService.currentBalance(memberId);
		boolean visitRecorded = false;
		UUID visitId = null;

		if (participation.isCompleted()) {
			PlaceEntity place = detail.place();
			VisitRecordResult visitResult = visitRecordService.recordIfAbsent(memberId, command.tripId(), missionId,
					place.getId(), occurredAt);
			visitRecorded = visitResult.recorded();
			visitId = visitResult.visitId();

			rewardPoints = mission.getRewardPoints();
			pointBalance = pointRewardService.reward(memberId, command.tripId(), participation.getId(), rewardPoints,
					mission.getTitle());

			events.publishEvent(
					new MissionCompleted(memberId, command.tripId(), missionId, participation.getId(), occurredAt));
		}

		Integer remainingAttempts = remainingAttempts(mission.getMaxAttempts(), participation);
		MissionSubmissionView result = new MissionSubmissionView(missionId, participation.isCompleted(),
				remainingAttempts, rewardPoints, pointBalance, visitRecorded, visitId);

		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, occurredAt.plus(RETENTION));
		idempotencyRequest.complete(200, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	private boolean determineCorrectness(MissionSubmissionCommand command, MissionEntity mission, UUID missionId) {
		return switch (command.type()) {
			case MULTIPLE_CHOICE -> isCorrectChoice(missionId, command.choiceId());
			case OX -> command.oxAnswer() != null && command.oxAnswer().equals(mission.getOxCorrectAnswer());
			case PHOTO -> throw new InvalidMissionSubmissionException();
		};
	}

	private boolean isCorrectChoice(UUID missionId, String choiceId) {
		UUID choiceUuid = parseChoiceId(choiceId);
		MissionChoiceEntity choice = missionChoiceRepository.findById(choiceUuid)
				.filter(candidate -> candidate.getMissionId().equals(missionId))
				.orElseThrow(InvalidMissionSubmissionException::new);
		return choice.isCorrect();
	}

	private UUID parseChoiceId(String choiceId) {
		try {
			return UUID.fromString(choiceId);
		} catch (IllegalArgumentException exception) {
			throw new InvalidMissionSubmissionException();
		}
	}

	private MissionSubmissionEntity toSubmissionEntity(UUID participationId, MissionSubmissionCommand command,
			boolean correct) {
		return switch (command.type()) {
			case MULTIPLE_CHOICE ->
				MissionSubmissionEntity.multipleChoice(participationId, parseChoiceId(command.choiceId()), correct);
			case OX -> MissionSubmissionEntity.ox(participationId, command.oxAnswer(), correct);
			case PHOTO -> throw new InvalidMissionSubmissionException();
		};
	}

	private Integer remainingAttempts(Integer maxAttempts, MissionParticipationEntity participation) {
		if (maxAttempts == null) {
			return null;
		}
		return participation.getStatus() == MissionStatus.AVAILABLE ? maxAttempts - participation.getAttemptCount() : 0;
	}

	private MissionSubmissionView replay(IdempotencyRequestEntity request, String requestHash) {
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
			throw new InvalidMissionSubmissionException();
		}
	}

	private String hash(UUID missionId, MissionSubmissionCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, missionId.toString());
			update(digest, command.tripId().toString());
			update(digest, command.type().name());
			update(digest, command.choiceId() == null ? "null" : command.choiceId());
			update(digest, command.oxAnswer() == null ? "null" : command.oxAnswer().toString());
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

	private String writeResponse(MissionSubmissionView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("미션 제출 응답을 저장할 수 없습니다.", exception);
		}
	}

	private MissionSubmissionView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new MissionSubmissionView(UUID.fromString(requiredText(data, "missionId")),
					requiredBoolean(data, "completed"), nullableInt(data, "remainingAttempts"),
					requiredInt(data, "rewardPoints"), requiredLong(data, "pointBalance"),
					requiredBoolean(data, "visitRecorded"), nullableUuid(data, "visitId"));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 미션 제출 응답을 읽을 수 없습니다.", exception);
		}
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 미션 제출 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 미션 제출 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}

	private boolean requiredBoolean(JsonNode node, String field) {
		return required(node, field).booleanValue();
	}

	private int requiredInt(JsonNode node, String field) {
		return required(node, field).intValue();
	}

	private long requiredLong(JsonNode node, String field) {
		return required(node, field).longValue();
	}

	private Integer nullableInt(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.intValue();
	}

	private UUID nullableUuid(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : UUID.fromString(value.stringValue());
	}

	public record MissionSubmissionCommand(UUID tripId, MissionType type, String choiceId, Boolean oxAnswer,
			LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	public record MissionSubmissionView(UUID missionId, boolean completed, Integer remainingAttempts, int rewardPoints,
			long pointBalance, boolean visitRecorded, UUID visitId) {
	}
}
