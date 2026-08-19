package com.buyeoon.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** 회원 인증(JWT)과 분리된 관리자 API Key 검증. /admin/** 요청에만 적용한다. */
public class AdminApiKeyFilter extends OncePerRequestFilter {

	private static final String HEADER_NAME = "X-Admin-Api-Key";

	private final String expectedApiKey;

	public AdminApiKeyFilter(String expectedApiKey) {
		this.expectedApiKey = expectedApiKey;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/admin/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String providedApiKey = request.getHeader(HEADER_NAME);
		if (expectedApiKey.isBlank() || providedApiKey == null || !expectedApiKey.equals(providedApiKey)) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter()
					.write("{\"success\":false,\"data\":{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}}");
			return;
		}
		filterChain.doFilter(request, response);
	}
}
