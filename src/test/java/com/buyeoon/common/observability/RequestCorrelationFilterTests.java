package com.buyeoon.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

	private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("Nginx와 Cloudflare 상관관계 값을 요청 처리 중 MDC에 넣고 응답에 요청 ID를 반환한다")
	void exposesSafeCorrelationValuesDuringRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-ID", "nginx-request-123");
		request.addHeader("CF-Ray", "cloudflare-ray-456-ICN");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (servletRequest, servletResponse) -> {
			assertThat(MDC.get("request_id")).isEqualTo("nginx-request-123");
			assertThat(MDC.get("cf_ray")).isEqualTo("cloudflare-ray-456-ICN");
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getHeader("X-Request-ID")).isEqualTo("nginx-request-123");
		assertThat(MDC.get("request_id")).isNull();
		assertThat(MDC.get("cf_ray")).isNull();
	}

	@Test
	@DisplayName("로그 주입이 가능한 요청 ID는 새 서버 요청 ID로 교체한다")
	void replacesUnsafeRequestId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-ID", "unsafe\nrequest-id");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			assertThat(MDC.get("request_id")).doesNotContain("\n");
			assertThat(MDC.get("request_id")).isNotEqualTo("unsafe\nrequest-id");
		});

		assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f-]{36}");
	}
}
