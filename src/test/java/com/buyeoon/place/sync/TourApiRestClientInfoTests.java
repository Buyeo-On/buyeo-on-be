package com.buyeoon.place.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TourApiRestClientInfoTests {

	private static final String BASE_URL = "https://tourapi.test/KorService2";

	private MockRestServiceServer server;
	private TourApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TourApiRestClient(builder, BASE_URL, "test-key", "34", "6");
	}

	/**
	 * detailInfo2 응답을 항목명 -> 내용 맵으로 읽는다. 항목명의 낱글자 공백은 붙이고, br 태그는 개행으로
	 * 바꾸며, 내용이 빈 항목은 결과에서 제외한다.
	 */
	@Test
	@DisplayName("detailInfo2 응답을 정제된 이용안내 맵으로 읽는다")
	void readsDetailInfoResponse() {
		server.expect(once(), requestTo(Matchers.containsString("/detailInfo2")))
				.andExpect(method(GET))
				.andExpect(requestTo(Matchers.containsString("contentId=126463")))
				.andExpect(requestTo(Matchers.containsString("contentTypeId=12")))
				.andRespond(withSuccess("""
						{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[
						{"contentid":"126463","infoname":"입 장 료","infotext":"무료"},
						{"contentid":"126463","infoname":"등산로","infotext":""},
						{"contentid":"126463","infoname":"이용가능시설","infotext":"[2층] <br>- 전시존"}
						]},"numOfRows":3,"pageNo":1,"totalCount":3}}}""", MediaType.APPLICATION_JSON));

		Map<String, String> info = client.fetchPlaceInfo(new TourApiAreaItem("126463", "12", null));

		assertThat(info).containsExactly(Map.entry("입장료", "무료"), Map.entry("이용가능시설", "[2층] \n- 전시존"));
		server.verify();
	}

	/** 이용안내가 등록되지 않은 장소는 items 자체가 빠진 응답이 오므로 빈 맵으로 처리한다. */
	@Test
	@DisplayName("items가 없는 응답이면 빈 맵을 돌려준다")
	void returnsEmptyMapWhenNoItems() {
		server.expect(once(), requestTo(Matchers.containsString("/detailInfo2")))
				.andRespond(withSuccess("""
						{"response":{"header":{"resultCode":"0000"},"body":{"numOfRows":0,"pageNo":1,"totalCount":0}}}""",
						MediaType.APPLICATION_JSON));

		assertThat(client.fetchPlaceInfo(new TourApiAreaItem("999", "39", null))).isEmpty();
		server.verify();
	}
}
