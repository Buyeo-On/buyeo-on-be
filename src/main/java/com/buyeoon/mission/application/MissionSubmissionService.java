package com.buyeoon.mission.application;

import com.buyeoon.badge.BadgeEvaluationService;
import com.buyeoon.badge.BadgeEvaluationService.AwardedBadgeResult;
import com.buyeoon.badge.BadgeMetric;
import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.common.storage.MissionPhotoObjectStore;
import com.buyeoon.common.storage.MissionPhotoObjectStore.MissionPhotoObject;
import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.mission.entity.MissionChoiceEntity;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionPhotoEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.entity.MissionSubmissionEntity;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionChoiceRepository;
import com.buyeoon.mission.repository.MissionIdempotencyRequestRepository;
import com.buyeoon.mission.repository.MissionParticipationRepository;
import com.buyeoon.mission.repository.MissionPhotoRepository;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
	private static final Set<String> ALLOWED_PHOTO_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final TripQueryService tripQueryService;
	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;
	private final MissionParticipationRepository missionParticipations;
	private final MissionSubmissionRepository missionSubmissions;
	private final MissionPhotoRepository missionPhotos;
	private final MissionPhotoObjectStore missionPhotoObjectStore;
	private final MissionIdempotencyRequestRepository idempotencyRequests;
	private final VisitRecordService visitRecordService;
	private final PointRewardService pointRewardService;
	private final BadgeEvaluationService badgeEvaluationService;
	private final PublicImageUrlService publicImageUrlService;
	private final ApplicationEventPublisher events;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionSubmissionService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			TripQueryService tripQueryService, MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, MissionParticipationRepository missionParticipations,
			MissionSubmissionRepository missionSubmissions, MissionPhotoRepository missionPhotos,
			MissionPhotoObjectStore missionPhotoObjectStore, MissionIdempotencyRequestRepository idempotencyRequests,
			VisitRecordService visitRecordService, PointRewardService pointRewardService,
			BadgeEvaluationService badgeEvaluationService, PublicImageUrlService publicImageUrlService,
			ApplicationEventPublisher events, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.tripQueryService = tripQueryService;
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
		this.missionParticipations = missionParticipations;
		this.missionSubmissions = missionSubmissions;
		this.missionPhotos = missionPhotos;
		this.missionPhotoObjectStore = missionPhotoObjectStore;
		this.idempotencyRequests = idempotencyRequests;
		this.visitRecordService = visitRecordService;
		this.pointRewardService = pointRewardService;
		this.badgeEvaluationService = badgeEvaluationService;
		this.publicImageUrlService = publicImageUrlService;
		this.events = events;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	public MissionSubmissionView submit(UUID memberId, UUID missionId, String idempotencyKey,
			MissionSubmissionCommand command) {
		validateIdempotencyKey(idempotencyKey);
		String requestHash = hash(missionId, command);
		MissionSubmissionResult result = Objects.requireNonNull(
				transactions.execute(
						status -> submitInTransaction(memberId, missionId, idempotencyKey, requestHash, command)),
				"미션 제출 트랜잭션 결과가 없습니다.");
		return toView(result);
	}

	private MissionSubmissionResult submitInTransaction(UUID memberId, UUID missionId, String idempotencyKey,
			String requestHash, MissionSubmissionCommand command) {
		long pointBalance = pointRewardService.currentBalance(memberId);
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

		UUID photoRecordId = command.type() == MissionType.PHOTO
				? verifyAndRecordPhoto(memberId, command.tripId(), missionId, command.photoId())
				: null;
		boolean correct = determineCorrectness(command, mission, missionId);
		participation.recordAttempt(correct, mission.getMaxAttempts(), occurredAt);
		missionSubmissions.save(toSubmissionEntity(participation.getId(), command, correct, photoRecordId));

		int rewardPoints = 0;
		boolean visitRecorded = false;
		UUID visitId = null;
		List<AwardedBadgeResult> newlyAwardedBadges = List.of();

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

			// badge Provider query가 방금 완료한 mission participation을 포함하도록 flush한 뒤
			// 판정한다(ADR-003).
			missionParticipations.flush();
			Set<BadgeMetric> affectedMetrics = EnumSet.of(BadgeMetric.MISSION_COMPLETED_COUNT);
			if (visitRecorded) {
				affectedMetrics.add(BadgeMetric.HERITAGE_VISITED_COUNT);
			}
			if (command.type() != MissionType.PHOTO) {
				affectedMetrics.add(BadgeMetric.QUIZ_CORRECT_COUNT);
			}
			newlyAwardedBadges = badgeEvaluationService.award(memberId, command.tripId(), affectedMetrics);
		}

		Integer remainingAttempts = remainingAttempts(mission.getMaxAttempts(), participation);
		MissionSubmissionResult result = new MissionSubmissionResult(missionId, participation.isCompleted(),
				remainingAttempts, rewardPoints, pointBalance, visitRecorded, visitId, newlyAwardedBadges);

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
			case PHOTO -> true;
		};
	}

	/** 발급 요청 정보(소유자·크기·Content-Type)와 실제 업로드된 S3 객체를 대조하고, 통과하면 사진 기록을 생성한다. */
	private UUID verifyAndRecordPhoto(UUID memberId, UUID tripId, UUID missionId, UUID photoId) {
		if (photoId == null) {
			throw new InvalidMissionSubmissionException();
		}
		String objectKey = MissionPhotoObjectKeys.key(tripId, missionId, photoId);
		MissionPhotoObject object = missionPhotoObjectStore.head(objectKey)
				.orElseThrow(MissionPhotoNotFoundException::new);
		if (!object.ownerId().equals(memberId)) {
			throw new MissionPhotoNotFoundException();
		}
		if (object.fileSizeBytes() != object.declaredFileSizeBytes()
				|| !object.contentType().equals(object.declaredContentType())
				|| !ALLOWED_PHOTO_CONTENT_TYPES.contains(object.contentType())) {
			throw new InvalidMissionSubmissionException();
		}
		MissionPhotoEntity photo = missionPhotos.save(MissionPhotoEntity.create(memberId, tripId, missionId, objectKey,
				object.contentType(), object.fileSizeBytes()));
		return photo.getId();
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
			boolean correct, UUID photoRecordId) {
		return switch (command.type()) {
			case MULTIPLE_CHOICE ->
				MissionSubmissionEntity.multipleChoice(participationId, parseChoiceId(command.choiceId()), correct);
			case OX -> MissionSubmissionEntity.ox(participationId, command.oxAnswer(), correct);
			case PHOTO -> MissionSubmissionEntity.photo(participationId, photoRecordId);
		};
	}

	private Integer remainingAttempts(Integer maxAttempts, MissionParticipationEntity participation) {
		if (maxAttempts == null) {
			return null;
		}
		return participation.getStatus() == MissionStatus.AVAILABLE ? maxAttempts - participation.getAttemptCount() : 0;
	}

	private MissionSubmissionResult replay(IdempotencyRequestEntity request, String requestHash) {
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
			update(digest, command.photoId() == null ? "null" : command.photoId().toString());
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

	private String writeResponse(MissionSubmissionResult result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("미션 제출 응답을 저장할 수 없습니다.", exception);
		}
	}

	private MissionSubmissionResult readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new MissionSubmissionResult(UUID.fromString(requiredText(data, "missionId")),
					requiredBoolean(data, "completed"), nullableInt(data, "remainingAttempts"),
					requiredInt(data, "rewardPoints"), requiredLong(data, "pointBalance"),
					requiredBoolean(data, "visitRecorded"), nullableUuid(data, "visitId"),
					readBadges(required(data, "newlyAwardedBadges")));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 미션 제출 응답을 읽을 수 없습니다.", exception);
		}
	}

	private List<AwardedBadgeResult> readBadges(JsonNode node) {
		if (!node.isArray()) {
			throw new IllegalStateException("저장된 미션 제출 응답의 배지 목록이 배열이 아닙니다.");
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
	private MissionSubmissionView toView(MissionSubmissionResult result) {
		List<AwardedBadgeView> badges = result.newlyAwardedBadges().stream()
				.map(badge -> new AwardedBadgeView(badge.badgeId(), badge.name(),
						badge.imageKey() != null ? publicImageUrlService.create(badge.imageKey()) : null,
						badge.condition(), badge.earnedAt()))
				.toList();
		return new MissionSubmissionView(result.missionId(), result.completed(), result.remainingAttempts(),
				result.rewardPoints(), result.pointBalance(), result.visitRecorded(), result.visitId(), badges);
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

	private String nullableText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.stringValue();
	}

	public record MissionSubmissionCommand(UUID tripId, MissionType type, String choiceId, Boolean oxAnswer,
			UUID photoId, LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	/** 멱등성 레코드에 저장하는 semantic result다. 배지 image key는 만료되는 URL 대신 그대로 저장한다. */
	private record MissionSubmissionResult(UUID missionId, boolean completed, Integer remainingAttempts,
			int rewardPoints, long pointBalance, boolean visitRecorded, UUID visitId,
			List<AwardedBadgeResult> newlyAwardedBadges) {
		private MissionSubmissionResult {
			newlyAwardedBadges = List.copyOf(newlyAwardedBadges);
		}
	}

	public record MissionSubmissionView(UUID missionId, boolean completed, Integer remainingAttempts, int rewardPoints,
			long pointBalance, boolean visitRecorded, UUID visitId, List<AwardedBadgeView> newlyAwardedBadges) {
		public MissionSubmissionView {
			newlyAwardedBadges = List.copyOf(newlyAwardedBadges);
		}
	}

	/** 요청마다 새로 서명한 10분 유효 Presigned URL을 담은 응답용 배지 뷰다. */
	public record AwardedBadgeView(UUID badgeId, String name, String imageUrl, String condition, Instant earnedAt) {
	}
}
