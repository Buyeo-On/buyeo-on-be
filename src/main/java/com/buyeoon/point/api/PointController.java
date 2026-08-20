package com.buyeoon.point.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.point.application.PointSettlementPreviewService;
import com.buyeoon.point.application.PointSettlementPreviewService.PointSettlementPreviewView;
import com.buyeoon.point.application.PointSummaryService;
import com.buyeoon.point.application.PointSummaryService.PointSummaryView;
import com.buyeoon.point.application.PointTransactionCursor;
import com.buyeoon.point.application.PointTransactionQueryService;
import com.buyeoon.point.application.PointTransactionQueryService.PointTransactionListView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 회원의 포인트 잔액 요약, 내역과 여행 정산 미리보기 조회를 담당하는 컨트롤러다. */
@RestController
public class PointController {

	private static final int DEFAULT_SIZE = 20;
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final PointSummaryService pointSummaryService;
	private final PointTransactionQueryService pointTransactionQueryService;
	private final PointSettlementPreviewService pointSettlementPreviewService;

	public PointController(PointSummaryService pointSummaryService,
			PointTransactionQueryService pointTransactionQueryService,
			PointSettlementPreviewService pointSettlementPreviewService) {
		this.pointSummaryService = pointSummaryService;
		this.pointTransactionQueryService = pointTransactionQueryService;
		this.pointSettlementPreviewService = pointSettlementPreviewService;
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
