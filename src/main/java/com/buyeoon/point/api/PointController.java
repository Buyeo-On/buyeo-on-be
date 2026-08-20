package com.buyeoon.point.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.point.application.PointSettlementPreviewService;
import com.buyeoon.point.application.PointSettlementPreviewService.PointSettlementPreviewView;
import com.buyeoon.point.application.PointSettlementService;
import com.buyeoon.point.application.PointSettlementService.TripSettlementView;
import com.buyeoon.point.application.PointSummaryService;
import com.buyeoon.point.application.PointSummaryService.PointSummaryView;
import com.buyeoon.point.application.PointTransactionCursor;
import com.buyeoon.point.application.PointTransactionQueryService;
import com.buyeoon.point.application.PointTransactionQueryService.PointTransactionListView;
import com.buyeoon.point.entity.SettlementChoice;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 로그인 회원의 포인트 잔액 요약, 내역, 여행 정산 미리보기 조회와 여행 포인트 정산 확정을 담당하는 컨트롤러다. */
@RestController
public class PointController {

	private static final int DEFAULT_SIZE = 20;
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final PointSummaryService pointSummaryService;
	private final PointTransactionQueryService pointTransactionQueryService;
	private final PointSettlementPreviewService pointSettlementPreviewService;
	private final PointSettlementService pointSettlementService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PointController(PointSummaryService pointSummaryService,
			PointTransactionQueryService pointTransactionQueryService,
			PointSettlementPreviewService pointSettlementPreviewService,
			PointSettlementService pointSettlementService) {
		this.pointSummaryService = pointSummaryService;
		this.pointTransactionQueryService = pointTransactionQueryService;
		this.pointSettlementPreviewService = pointSettlementPreviewService;
		this.pointSettlementService = pointSettlementService;
	}

	@GetMapping("/members/me/points")
	public SuccessResponse<PointSummaryView> getMyPointSummary(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(pointSummaryService.getSummary(memberId));
	}

	@GetMapping("/members/me/point-transactions")
	public SuccessResponse<PointTransactionListView> getMyPointTransactions(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String size) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(pointTransactionQueryService.list(memberId, parseCursor(cursor), parseSize(size)));
	}

	@GetMapping("/trips/{tripId}/settlement-preview")
	public SuccessResponse<PointSettlementPreviewView> getTripSettlementPreview(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID tripId) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(pointSettlementPreviewService.preview(memberId, tripId));
	}

	@PutMapping("/trips/{tripId}/settlement")
	public SuccessResponse<TripSettlementView> settleTripPoints(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID tripId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		SettlementChoice choice = parseChoice(request);
		return SuccessResponse.of(pointSettlementService.settle(memberId, tripId, idempotencyKey, choice));
	}

	private SettlementChoice parseChoice(JsonNode request) {
		if (request == null || request.isNull()) {
			return null;
		}
		if (!request.isObject() || !hasOnlyOptionalChoice(request)) {
			throw new InvalidPointRequestException();
		}
		JsonNode choiceNode = request.get("choice");
		if (choiceNode == null || choiceNode.isNull()) {
			return null;
		}
		if (!choiceNode.isString()) {
			throw new InvalidPointRequestException();
		}
		return switch (choiceNode.stringValue()) {
			case "LEAVE_TO_BUYEO" -> SettlementChoice.LEAVE_TO_BUYEO;
			case "CARRY_OVER" -> SettlementChoice.CARRY_OVER;
			default -> throw new InvalidPointRequestException();
		};
	}

	private boolean hasOnlyOptionalChoice(JsonNode node) {
		return node.size() == 0 || (node.size() == 1 && node.has("choice"));
	}

	private PointTransactionCursor parseCursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			return PointTransactionCursor.decode(cursor);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPointRequestException();
		}
	}

	private int parseSize(String size) {
		if (size == null) {
			return DEFAULT_SIZE;
		}
		int value;
		try {
			value = Integer.parseInt(size);
		} catch (NumberFormatException exception) {
			throw new InvalidPointRequestException();
		}
		if (value < MIN_SIZE || value > MAX_SIZE) {
			throw new InvalidPointRequestException();
		}
		return value;
	}
}
