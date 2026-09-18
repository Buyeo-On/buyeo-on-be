package com.buyeoon.place.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TourApiRestClientAccessibilityTests {

	private static final String BASE_URL = "https://tourapi.test/KorService2";

	private static final String WITH_BASE_URL = "https://tourapi.test/KorWithService2";

	private MockRestServiceServer server;
	private TourApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TourApiRestClient(builder, BASE_URL, WITH_BASE_URL, "test-key", "34", "6", "44", "760", "126.9098",
				"36.2754", "20000");
	}

	/**
	 * detailWithTour2 응답을 "무장애:" 접두사 맵으로 읽는다. 접두사는 이용안내와 출처를 구분하고 클라이언트가 개별 키 이름에
	 * 묶이지 않게 한다. 값 끝의 분류 꼬리표("_무장애 편의시설")는 떼어 낸다.
	 */
	@Test
	@DisplayName("detailWithTour2 응답을 무장애 접두사가 붙은 맵으로 읽는다")
	void readsAccessibilityResponse() {
		server.expect(once(), requestTo(Matchers.containsString("/detailWithTour2"))).andExpect(method(GET))
				.andExpect(requestTo(Matchers.containsString("contentId=250259"))).andRespond(withSuccess("""
						{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[
						{"contentid":"250259",\
						"route":"출입구까지 평지로 되어 있음_무장애 편의시설",\
						"wheelchair":"대여 가능(1대/왕릉원 매표소)",\
						"restroom":"장애인 화장실 있음",\
						"braileblock":"점자블록 있음(고분군 화장실)_시각장애인 편의시설"}
						]},"numOfRows":1,"pageNo":1,"totalCount":1}}}""", MediaType.APPLICATION_JSON));

		Map<String, String> accessibility = client.fetchAccessibility(new TourApiAreaItem("250259", "12", null));

		assertThat(accessibility).containsExactly(Map.entry("무장애:접근로", "출입구까지 평지로 되어 있음"),
				Map.entry("무장애:휠체어", "대여 가능(1대/왕릉원 매표소)"), Map.entry("무장애:화장실", "장애인 화장실 있음"),
				Map.entry("무장애:점자블록", "점자블록 있음(고분군 화장실)"));
		server.verify();
	}

	/** 값이 빈 항목은 맵에 넣지 않는다. 부여 실측 9곳도 네 항목이 모두 차 있지는 않다. */
	@Test
	@DisplayName("값이 빈 항목은 제외한다")
	void skipsBlankFields() {
		server.expect(once(), requestTo(Matchers.containsString("/detailWithTour2"))).andRespond(withSuccess("""
				{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[
				{"contentid":"126699","route":"","wheelchair":"대여 불가","restroom":"","braileblock":""}
				]},"numOfRows":1,"pageNo":1,"totalCount":1}}}""", MediaType.APPLICATION_JSON));

		assertThat(client.fetchAccessibility(new TourApiAreaItem("126699", "12", null)))
				.containsExactly(Map.entry("무장애:휠체어", "대여 불가"));
		server.verify();
	}

	/** 무장애 정보가 없는 장소가 대부분(부여 118곳 중 109곳)이므로 빈 응답이 정상이다. */
	@Test
	@DisplayName("items가 없는 응답이면 빈 맵을 돌려준다")
	void returnsEmptyMapWhenNoItems() {
		server.expect(once(), requestTo(Matchers.containsString("/detailWithTour2"))).andRespond(withSuccess("""
				{"response":{"header":{"resultCode":"0000"},"body":{"numOfRows":0,"pageNo":1,"totalCount":0}}}""",
				MediaType.APPLICATION_JSON));

		assertThat(client.fetchAccessibility(new TourApiAreaItem("999", "39", null))).isEmpty();
		server.verify();
	}

	/** 결과가 없을 때 items 자리에 빈 문자열이 오는 TourAPI 관행은 무장애 API도 같다. */
	@Test
	@DisplayName("items가 빈 문자열인 응답이면 빈 맵을 돌려준다")
	void returnsEmptyMapWhenItemsIsEmptyString() {
		server.expect(once(), requestTo(Matchers.containsString("/detailWithTour2"))).andRespond(withSuccess("""
				{"response":{"header":{"resultCode":"0000"},"body":{\
				"items":"","numOfRows":0,"pageNo":1,"totalCount":0}}}""", MediaType.APPLICATION_JSON));

		assertThat(client.fetchAccessibility(new TourApiAreaItem("2926985", "39", null))).isEmpty();
		server.verify();
	}

	/**
	 * 무장애 조회가 실패해도 예외를 밖으로 던지지 않는다. 던지면 PlaceSyncService의 catch에 걸려 멀쩡한 장소가 동기화
	 * 실패로 집계된다.
	 */
	@Test
	@DisplayName("조회가 실패해도 예외 대신 빈 맵을 돌려준다")
	void returnsEmptyMapWhenRequestFails() {
		server.expect(once(), requestTo(Matchers.containsString("/detailWithTour2"))).andRespond(withServerError());

		assertThat(client.fetchAccessibility(new TourApiAreaItem("250259", "12", null))).isEmpty();
		server.verify();
	}
}
