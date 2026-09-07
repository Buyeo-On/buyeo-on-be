package com.buyeoon.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청에 상관관계 ID를 붙이고, 처리가 끝나면 요청 한 줄 로그를 남긴다.
 *
 * <p>Spring은 정상 요청을 스스로 기록하지 않아 운영 로그에 기동·종료만 남았다. 메서드·경로·상태·처리 시간과 인증된 회원
 * ID를 구조화 필드(MDC)로 남겨 Nginx access 로그와 request_id로 대조할 수 있게 한다. 쿼리스트링과 본문은 ADR-009에 따라
 * 남기지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

	/** 인증 필터가 요청 속성으로 남긴 회원 ID. Security 컨텍스트는 체인이 끝나면 비워지므로 속성으로 받는다. */
	public static final String MEMBER_ID_ATTRIBUTE = "buyeoon.member_id";

	private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);
	private static final String REQUEST_ID_HEADER = "X-Request-ID";
	private static final String CF_RAY_HEADER = "CF-Ray";
	private static final String ACTUATOR_PREFIX = "/actuator";
	private static final Pattern SAFE_CORRELATION_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
	private static final long NANOS_PER_MILLI = 1_000_000L;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = safeHeader(request, REQUEST_ID_HEADER);
		if (requestId == null) {
			requestId = UUID.randomUUID().toString();
		}
		String cfRay = safeHeader(request, CF_RAY_HEADER);

		response.setHeader(REQUEST_ID_HEADER, requestId);
		MDC.put("request_id", requestId);
		if (cfRay != null) {
			MDC.put("cf_ray", cfRay);
		}
		long startedAt = System.nanoTime();
		// 체인이 예외로 끝나면 응답 상태를 믿을 수 없으므로 500으로 기록한다.
		int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
		try {
			filterChain.doFilter(request, response);
			status = response.getStatus();
		} finally {
			logRequest(request, status, startedAt);
			MDC.remove("request_id");
			MDC.remove("cf_ray");
		}
	}

	private void logRequest(HttpServletRequest request, int status, long startedAt) {
		String path = request.getRequestURI();
		if (path == null || path.startsWith(ACTUATOR_PREFIX)) {
			// 헬스체크가 로그 대부분을 차지하지 않도록 뺀다.
			return;
		}
		long durationMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;
		MDC.put("http_method", request.getMethod());
		MDC.put("http_path", path);
		MDC.put("http_status", Integer.toString(status));
		MDC.put("duration_ms", Long.toString(durationMs));
		Object memberId = request.getAttribute(MEMBER_ID_ATTRIBUTE);
		if (memberId != null) {
			MDC.put("member_id", memberId.toString());
		}
		try {
			if (status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
				LOG.error("{} {} {} {}ms", request.getMethod(), path, status, durationMs);
			} else if (status >= HttpServletResponse.SC_BAD_REQUEST) {
				LOG.warn("{} {} {} {}ms", request.getMethod(), path, status, durationMs);
			} else {
				LOG.info("{} {} {} {}ms", request.getMethod(), path, status, durationMs);
			}
		} finally {
			MDC.remove("http_method");
			MDC.remove("http_path");
			MDC.remove("http_status");
			MDC.remove("duration_ms");
			MDC.remove("member_id");
		}
	}

	private String safeHeader(HttpServletRequest request, String headerName) {
		String value = request.getHeader(headerName);
		return value != null && SAFE_CORRELATION_VALUE.matcher(value).matches() ? value : null;
	}
}
