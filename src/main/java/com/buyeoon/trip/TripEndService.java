package com.buyeoon.trip;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.ResourceNotFoundException;
import com.buyeoon.trip.TripStartService.TripView;
import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

/** UC-23 여행 종료 커맨드 서비스다. TripStartService의 멱등성/락 패턴을 재사용한다. */
@Service
public class TripEndService implements TripEnder {

	private static final String OPERATION = "END_TRIP";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final TripRepository trips;
	private final IdempotencyRequestRepository idempotencyRequests;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	public TripEndService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			TripRepository trips, IdempotencyRequestRepository idempotencyRequests, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.trips = trips;
		this.idempotencyRequests = idempotencyRequests;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	@Override
	public TripView end(UUID memberId, UUID tripId, String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		return Objects.requireNonNull(
				transactions.execute(status -> endInTransaction(memberId, tripId, idempotencyKey)),
				"여행 종료 트랜잭션 결과가 없습니다.");
	}

	private TripView endInTransaction(UUID memberId, UUID tripId, String idempotencyKey) {
		Instant endedAt = clockTimestamp();
		String requestHash = tripId.toString();

		IdempotencyRequestId idempotencyId = new IdempotencyRequestId(memberId, idempotencyKey);
		Optional<IdempotencyRequestEntity> existingRequest = idempotencyRequests.findById(idempotencyId);
		if (existingRequest.isPresent()) {
			IdempotencyRequestEntity request = existingRequest.get();
			if (!request.getExpiresAt().isAfter(endedAt)) {
				idempotencyRequests.delete(request);
			} else {
				return replay(request, requestHash);
			}
		}

		TripEntity trip = trips.lockByIdAndMemberId(tripId, memberId).orElseThrow(ResourceNotFoundException::new);
		if (trip.getStatus() != TripStatus.IN_PROGRESS) {
			throw new InvalidStateTransitionException();
		}

		trip.end(endedAt);

		TripView result = new TripView(trip.getId(), trip.getStatus(), trip.getStartedAt().atZone(ASIA_SEOUL),
				trip.getEndedAt().atZone(ASIA_SEOUL), null);
		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, endedAt.plus(RETENTION));
		idempotencyRequest.complete(200, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	private TripView replay(IdempotencyRequestEntity request, String requestHash) {
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
			throw new InvalidTripStartRequestException();
		}
	}

	private String writeResponse(TripView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("여행 종료 응답을 저장할 수 없습니다.", exception);
		}
	}

	private TripView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new TripView(UUID.fromString(requiredText(data, "tripId")),
					TripStatus.valueOf(requiredText(data, "status")),
					ZonedDateTime.parse(requiredText(data, "startedAt")), nullableDateTime(data, "endedAt"),
					nullableDateTime(data, "settledAt"));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 여행 종료 응답을 읽을 수 없습니다.", exception);
		}
	}

	private ZonedDateTime nullableDateTime(JsonNode data, String field) {
		JsonNode value = data.get(field);
		return value == null || value.isNull() ? null : ZonedDateTime.parse(value.stringValue());
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 여행 종료 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 여행 종료 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}
}
