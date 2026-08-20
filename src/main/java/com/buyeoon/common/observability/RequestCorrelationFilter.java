package com.buyeoon.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

	private static final String REQUEST_ID_HEADER = "X-Request-ID";
	private static final String CF_RAY_HEADER = "CF-Ray";
	private static final Pattern SAFE_CORRELATION_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

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
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove("request_id");
			MDC.remove("cf_ray");
		}
	}

	private String safeHeader(HttpServletRequest request, String headerName) {
		String value = request.getHeader(headerName);
		return value != null && SAFE_CORRELATION_VALUE.matcher(value).matches() ? value : null;
	}
}
