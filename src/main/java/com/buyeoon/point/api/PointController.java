package com.buyeoon.point.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.point.application.PointSummaryService;
import com.buyeoon.point.application.PointSummaryService.PointSummaryView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 회원의 포인트 잔액 요약 조회를 담당하는 컨트롤러다. */
@RestController
public class PointController {

	private final PointSummaryService pointSummaryService;

	public PointController(PointSummaryService pointSummaryService) {
		this.pointSummaryService = pointSummaryService;
	}

	@GetMapping("/members/me/points")
	public SuccessResponse<PointSummaryView> getMyPointSummary(@AuthenticationPrincipal Jwt jwt) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(pointSummaryService.getSummary(memberId));
	}
}
