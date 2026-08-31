package com.buyeoon.place.sync;

import com.buyeoon.place.entity.PlaceImageLicenseType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TourApiRestClientAttributionTests {

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

	@Test
	@DisplayName("detailCommon2의 Type3 이미지 저작권 유형을 정규화한다")
	void mapsType3ImageLicenseFromDetailCommon() {
		server.expect(once(), requestTo(Matchers.containsString("/detailCommon2"))).andExpect(method(GET))
				.andRespond(withSuccess("""
						{"response":{"body":{"items":{"item":[{
						"title":"정림사지","overview":"설명","addr1":"충남 부여군",
						"firstimage":"https://tourapi.example.com/image.jpg","cpyrhtDivCd":"Type3",
						"mapx":"126.9098","mapy":"36.2754"
						}]}}}}""", MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.containsString("/detailIntro2"))).andExpect(method(GET))
				.andRespond(withSuccess("""
						{"response":{"body":{"items":{"item":[]}}}}""", MediaType.APPLICATION_JSON));

		TourApiPlaceDetail detail = client.fetchPlaceDetail(new TourApiAreaItem("126463", "12", null));

		assertThat(detail.sourceImageLicenseType()).isEqualTo(PlaceImageLicenseType.KOGL_TYPE_3);
		server.verify();
	}

	@Test
	@DisplayName("알 수 없는 이미지 저작권 코드는 동기화를 중단하지 않고 null로 둔다")
	void keepsUnknownImageLicenseNullable() {
		server.expect(once(), requestTo(Matchers.containsString("/detailCommon2"))).andExpect(method(GET))
				.andRespond(withSuccess("""
						{"response":{"body":{"items":{"item":[{
						"title":"정림사지","overview":"설명","addr1":"충남 부여군",
						"firstimage":"https://tourapi.example.com/image.jpg","cpyrhtDivCd":"Unexpected",
						"mapx":"126.9098","mapy":"36.2754"
						}]}}}}""", MediaType.APPLICATION_JSON));
		server.expect(once(), requestTo(Matchers.containsString("/detailIntro2"))).andExpect(method(GET))
				.andRespond(withSuccess("""
						{"response":{"body":{"items":{"item":[]}}}}""", MediaType.APPLICATION_JSON));

		TourApiPlaceDetail detail = client.fetchPlaceDetail(new TourApiAreaItem("126463", "12", null));

		assertThat(detail.sourceImageLicenseType()).isNull();
		server.verify();
	}
}
