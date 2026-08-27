package com.buyeoon.place.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * 공공데이터포털 TourAPI 2.0(areaBasedList2, detailCommon2, detailIntro2, detailInfo2)
 * 호출 구현체. 표준 JSON 응답 포맷 {@code response.body.items.item}과 표준 필드명(usetime,
 * usefee)을 따른다.
 */
class TourApiRestClient implements TourApiClient {

	private final RestClient restClient;
	private final String serviceKey;
	private final String areaCode;
	private final String signguCode;

	TourApiRestClient(RestClient.Builder restClientBuilder, String baseUrl, String serviceKey, String areaCode,
			String signguCode) {
		this.restClient = restClientBuilder.baseUrl(baseUrl)
				.messageConverters(converters -> converters.addFirst(jsonConverter())).build();
		this.serviceKey = serviceKey;
		this.areaCode = areaCode;
		this.signguCode = signguCode;
	}

	@Override
	public List<TourApiAreaItem> fetchAreaItems() {
		TourApiListResponse response = restClient.get()
				.uri(uriBuilder -> withCommonParams(uriBuilder.path("/areaBasedList2")).queryParam("areaCode", areaCode)
						.queryParam("sigunguCode", signguCode).queryParam("numOfRows", "1000").build())
				.retrieve().body(TourApiListResponse.class);
		return items(response).stream()
				.map(item -> new TourApiAreaItem(item.contentid(), item.contenttypeid(), item.cat3())).toList();
	}

	@Override
	public TourApiPlaceDetail fetchPlaceDetail(TourApiAreaItem item) {
		TourApiCommonResponse common = restClient.get()
				.uri(uriBuilder -> withCommonParams(uriBuilder.path("/detailCommon2"))
						.queryParam("contentId", item.contentId()).build())
				.retrieve().body(TourApiCommonResponse.class);
		TourApiCommonItem commonItem = commonItems(common).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("detailCommon2 응답이 비어 있습니다: " + item.contentId()));

		TourApiIntroResponse intro = restClient.get()
				.uri(uriBuilder -> withCommonParams(uriBuilder.path("/detailIntro2"))
						.queryParam("contentId", item.contentId()).queryParam("contentTypeId", item.contentTypeId())
						.build())
				.retrieve().body(TourApiIntroResponse.class);
		TourApiIntroItem introItem = introItems(intro).stream().findFirst().orElse(null);

		return new TourApiPlaceDetail(item.contentId(), commonItem.title(), commonItem.overview(), commonItem.addr1(),
				commonItem.firstimage(), Double.parseDouble(commonItem.mapy()), Double.parseDouble(commonItem.mapx()),
				introItem == null ? null : introItem.usetime(), introItem == null ? null : introItem.usefee(),
				Map.of());
	}

	@Override
	public Map<String, String> fetchPlaceInfo(TourApiAreaItem item) {
		TourApiInfoResponse response = restClient.get()
				.uri(uriBuilder -> withCommonParams(uriBuilder.path("/detailInfo2"))
						.queryParam("contentId", item.contentId()).queryParam("contentTypeId", item.contentTypeId())
						.queryParam("numOfRows", "30").build())
				.retrieve().body(TourApiInfoResponse.class);
		return TourApiInfoSanitizer.sanitize(infoItems(response));
	}

	private UriBuilder withCommonParams(UriBuilder uriBuilder) {
		return uriBuilder.queryParam("serviceKey", serviceKey).queryParam("MobileOS", "ETC")
				.queryParam("MobileApp", "BuyeoOn").queryParam("_type", "json");
	}

	private List<TourApiAreaItemDto> items(TourApiListResponse response) {
		if (response == null || response.response() == null || response.response().body() == null
				|| response.response().body().items() == null) {
			return List.of();
		}
		return response.response().body().items().item();
	}

	private List<TourApiCommonItem> commonItems(TourApiCommonResponse response) {
		if (response == null || response.response() == null || response.response().body() == null
				|| response.response().body().items() == null) {
			return List.of();
		}
		return response.response().body().items().item();
	}

	private List<TourApiIntroItem> introItems(TourApiIntroResponse response) {
		if (response == null || response.response() == null || response.response().body() == null
				|| response.response().body().items() == null) {
			return List.of();
		}
		return response.response().body().items().item();
	}

	private List<TourApiInfoItem> infoItems(TourApiInfoResponse response) {
		if (response == null || response.response() == null || response.response().body() == null
				|| response.response().body().items() == null) {
			return List.of();
		}
		return response.response().body().items().item();
	}

	private record TourApiListResponse(TourApiListResponseBody response) {
		record TourApiListResponseBody(TourApiListBody body) {
		}

		record TourApiListBody(TourApiListItems items) {
		}

		record TourApiListItems(List<TourApiAreaItemDto> item) {
		}
	}

	private record TourApiAreaItemDto(String contentid, String contenttypeid, String cat3) {
	}

	private record TourApiCommonResponse(TourApiCommonResponseBody response) {
		record TourApiCommonResponseBody(TourApiCommonBody body) {
		}

		record TourApiCommonBody(TourApiCommonItems items) {
		}

		record TourApiCommonItems(List<TourApiCommonItem> item) {
		}
	}

	private record TourApiCommonItem(String title, String overview, String addr1, String firstimage, String mapx,
			String mapy) {
	}

	private record TourApiIntroResponse(TourApiIntroResponseBody response) {
		record TourApiIntroResponseBody(TourApiIntroBody body) {
		}

		record TourApiIntroBody(TourApiIntroItems items) {
		}

		record TourApiIntroItems(List<TourApiIntroItem> item) {
		}
	}

	private record TourApiIntroItem(String usetime, String usefee) {
	}

	private record TourApiInfoResponse(TourApiInfoResponseBody response) {
		record TourApiInfoResponseBody(TourApiInfoBody body) {
		}

		/** 이용안내가 없는 장소는 items를 객체가 아닌 빈 문자열로 돌려주므로 null로 받는다. */
		record TourApiInfoBody(TourApiInfoItems items) {
		}

		record TourApiInfoItems(List<TourApiInfoItem> item) {
		}
	}

	/**
	 * TourAPI는 결과가 없을 때 {@code "items": ""}처럼 객체 자리에 빈 문자열을 돌려준다. 역직렬화 실패로 항목 전체가
	 * 동기화 실패 처리되지 않도록 빈 문자열을 null 객체로 받는다.
	 */
	private static MappingJackson2HttpMessageConverter jsonConverter() {
		ObjectMapper objectMapper = new ObjectMapper().enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		return new MappingJackson2HttpMessageConverter(objectMapper);
	}
}
