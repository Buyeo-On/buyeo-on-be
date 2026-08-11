package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.MemberQueryService;
import com.buyeoon.member.application.MemberQueryService.MemberView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/members")
public class MemberController {

	private final MemberQueryService memberQueryService;

	public MemberController(MemberQueryService memberQueryService) {
		this.memberQueryService = memberQueryService;
	}

	@GetMapping("/me")
	public SuccessResponse<MemberView> getMyMember(@AuthenticationPrincipal Jwt jwt) {
		return SuccessResponse
				.of(memberQueryService.getActiveMember(UUID.fromString(Objects.requireNonNull(jwt.getSubject()))));
	}
}
