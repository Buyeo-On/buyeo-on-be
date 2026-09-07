package com.buyeoon.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {

	private final RequestCorrelationFilter filter = new RequestCorrelationFilter();
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);

	@BeforeEach
	void captureLogs() {
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void clearMdc() {
		logger.detachAppender(logs);
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

	@Test
	@DisplayName("요청이 끝나면 메서드·경로·상태·처리 시간·회원 ID를 구조화 필드로 남긴 한 줄 로그를 쓴다")
	void logsOneLinePerRequestWithStructuredFields() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/places");
		request.setQueryString("latitude=36.28&longitude=126.91");
		request.addHeader("X-Request-ID", "nginx-request-123");
		request.setAttribute(RequestCorrelationFilter.MEMBER_ID_ATTRIBUTE, "550e8400-e29b-41d4-a716-446655440000");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_OK);
		});

		assertThat(logs.list).hasSize(1);
		ILoggingEvent event = logs.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).matches("GET /places 200 \\d+ms");
		assertThat(event.getMDCPropertyMap())
				.containsEntry("request_id", "nginx-request-123")
				.containsEntry("http_method", "GET")
				.containsEntry("http_path", "/places")
				.containsEntry("http_status", "200")
				.containsEntry("member_id", "550e8400-e29b-41d4-a716-446655440000")
				.containsKey("duration_ms");
		// 쿼리스트링은 개인정보(좌표)가 실릴 수 있어 남기지 않는다.
		assertThat(event.getFormattedMessage()).doesNotContain("latitude");
		assertThat(event.getMDCPropertyMap().values()).noneMatch(value -> value.contains("latitude"));
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	@DisplayName("4xx는 WARN, 5xx는 ERROR로 남기고 회원 ID가 없으면 필드를 비운다")
	void usesLevelByStatusAndOmitsMemberIdWhenAnonymous() throws Exception {
		filter.doFilter(new MockHttpServletRequest("POST", "/members/me/term-consents"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_UNAUTHORIZED));
		filter.doFilter(new MockHttpServletRequest("GET", "/terms"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_BAD_GATEWAY));

		assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR);
		assertThat(logs.list.get(0).getFormattedMessage()).startsWith("POST /members/me/term-consents 401 ");
		assertThat(logs.list.get(0).getMDCPropertyMap()).doesNotContainKey("member_id");
	}

	@Test
	@DisplayName("체인이 예외로 끝나면 500으로 기록하고 예외는 그대로 전파한다")
	void logsServerErrorWhenChainThrows() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/places");
		FilterChain failingChain = (servletRequest, servletResponse) -> {
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failingChain))
				.isInstanceOf(ServletException.class);

		assertThat(logs.list).hasSize(1);
		assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
		assertThat(logs.list.get(0).getFormattedMessage()).startsWith("GET /places 500 ");
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	@DisplayName("Actuator 헬스체크는 요청 로그를 남기지 않는다")
	void skipsActuatorRequests() throws Exception {
		filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_OK));

		assertThat(logs.list).isEmpty();
	}

	private static FilterChain respondWith(int status) {
		return (servletRequest, servletResponse) -> ((HttpServletResponse) servletResponse).setStatus(status);
	}
}
