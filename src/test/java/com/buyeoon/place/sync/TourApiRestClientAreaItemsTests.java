package com.buyeoon.place.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * areaCode·법정동·위치기반 세 조회를 합쳐 부여 장소 목록을 모으는 fetchAreaItems를 검증한다. 실제 TourAPI 응답을
 * 부여 지역에 실측했을 때 세 조회가 서로 다른 등록 항목을 돌려주는 것을 확인했다(areaCode 100건, 법정동 200건, 위치기반
 * 162건, 합집합 262건).
 */
class TourApiRestClientAreaItemsTests {

	private static final String BASE_URL = "https://tourapi.test/KorService2";

	private MockRestServiceServer server;
	private TourApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TourApiRestClient(builder, BASE_URL, "test-key", "34", "6", "44", "760", "126.9098", "36.2754",
				"20000");
	}

	private static String listBody(String... rows) {
		return """
				{"response":{"header":{"resultCode":"0000"},\
				"body":{"items":{"item":[%s]},"numOfRows":1,"pageNo":1,"totalCount":1}}}
				""".formatted(String.join(",", rows));
	}

	private static String item(String contentId, String contentTypeId, String mapx, String mapy) {
		return """
				{"contentid":"%s","contenttypeid":"%s","mapx":"%s","mapy":"%s"}
				""".formatted(contentId, contentTypeId, mapx, mapy).strip();
	}

	/** 세 조회에 겹치지 않는 항목이 각각 있으면 셋 다 합쳐서 돌려준다. */
	@Test
	@DisplayName("areaCode·법정동·위치기반 세 조회를 합친다")
	void mergesThreeQueries() {
		server.expect(requestTo(
				Matchers.allOf(Matchers.containsString("/areaBasedList2"), Matchers.containsString("areaCode=34"))))
				.andRespond(withSuccess(listBody(item("1001", "12", "126.90", "36.27")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(
				Matchers.allOf(Matchers.containsString("/areaBasedList2"), Matchers.containsString("lDongRegnCd=44"))))
				.andRespond(withSuccess(listBody(item("1002", "12", "126.91", "36.28")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(Matchers.containsString("/locationBasedList2")))
				.andRespond(withSuccess(listBody(item("1003", "12", "126.92", "36.29")), MediaType.APPLICATION_JSON));

		List<TourApiAreaItem> items = client.fetchAreaItems();

		assertThat(items).extracting(TourApiAreaItem::contentId).containsExactlyInAnyOrder("1001", "1002", "1003");
		server.verify();
	}

	/** 같은 contentId가 여러 조회에 나오면 한 번만 남기고, 좌표는 처음 만난 값을 유지한다. */
	@Test
	@DisplayName("같은 장소가 여러 조회에 겹치면 중복 제거한다")
	void deduplicatesByContentId() {
		server.expect(requestTo(Matchers.containsString("areaCode=34")))
				.andRespond(withSuccess(listBody(item("2001", "12", "126.90", "36.27")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(Matchers.containsString("lDongRegnCd=44")))
				.andRespond(withSuccess(listBody(item("2001", "12", "999.99", "999.99")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(Matchers.containsString("/locationBasedList2")))
				.andRespond(withSuccess(listBody(), MediaType.APPLICATION_JSON));

		List<TourApiAreaItem> items = client.fetchAreaItems();

		assertThat(items).hasSize(1);
		assertThat(items.get(0).latitude()).isEqualTo(36.27);
		server.verify();
	}

	/** 목록 응답의 좌표를 그대로 싣어 상세 호출 없이 경계 판정에 쓸 수 있게 한다. */
	@Test
	@DisplayName("목록 응답 좌표를 그대로 담는다")
	void carriesCoordinatesFromListResponse() {
		server.expect(requestTo(Matchers.containsString("areaCode=34"))).andRespond(withSuccess(
				listBody(item("3001", "39", "126.9128955516", "36.2790710570")), MediaType.APPLICATION_JSON));
		server.expect(requestTo(Matchers.containsString("lDongRegnCd=44")))
				.andRespond(withSuccess(listBody(), MediaType.APPLICATION_JSON));
		server.expect(requestTo(Matchers.containsString("/locationBasedList2")))
				.andRespond(withSuccess(listBody(), MediaType.APPLICATION_JSON));

		TourApiAreaItem item = client.fetchAreaItems().get(0);

		assertThat(item.hasCoordinates()).isTrue();
		assertThat(item.longitude()).isEqualTo(126.9128955516);
		assertThat(item.latitude()).isEqualTo(36.2790710570);
		server.verify();
	}
}
