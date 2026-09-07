package com.buyeoon.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthenticatedMemberAttributeFilterTests {

	private final AuthenticatedMemberAttributeFilter filter = new AuthenticatedMemberAttributeFilter();

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("JWT로 인증된 요청이면 subject(회원 ID)를 요청 속성에 남긴다")
	void copiesJwtSubjectToRequestAttribute() throws Exception {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("550e8400-e29b-41d4-a716-446655440000").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/members/me");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertThat(request.getAttribute(RequestCorrelationFilter.MEMBER_ID_ATTRIBUTE))
				.isEqualTo("550e8400-e29b-41d4-a716-446655440000");
	}

	@Test
	@DisplayName("인증되지 않은 요청에는 회원 ID 속성을 남기지 않는다")
	void leavesAttributeEmptyWhenAnonymous() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/terms");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertThat(request.getAttribute(RequestCorrelationFilter.MEMBER_ID_ATTRIBUTE)).isNull();
	}
}
