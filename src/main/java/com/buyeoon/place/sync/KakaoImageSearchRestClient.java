package com.buyeoon.place.sync;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class KakaoImageSearchRestClient implements KakaoImageSearchClient {

	private static final Logger log = LoggerFactory.getLogger(KakaoImageSearchRestClient.class);

	private final RestClient restClient;
	private final String restApiKey;

	KakaoImageSearchRestClient(RestClient.Builder restClientBuilder, String restApiKey) {
		this.restClient = restClientBuilder.build();
		this.restApiKey = restApiKey;
	}

	@Override
	public Optional<String> findFirstImageUrl(String placeName, String address) {
		if (restApiKey.isBlank() || placeName == null || placeName.isBlank()) {
			return Optional.empty();
		}

		String query = address == null || address.isBlank() ? placeName : placeName + " " + address;
		try {
			KakaoImageSearchResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder.path("/v2/search/image").queryParam("query", query)
							.queryParam("size", 1).build())
					.header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey).retrieve()
					.body(KakaoImageSearchResponse.class);
			if (response == null || response.documents() == null) {
				return Optional.empty();
			}
			return response.documents().stream().map(KakaoImageDocument::imageUrl)
					.filter(url -> url != null && !url.isBlank())
					.findFirst();
		} catch (RestClientException exception) {
			log.warn("카카오 이미지 검색 실패: placeName={}", placeName, exception);
			return Optional.empty();
		}
	}

	private record KakaoImageSearchResponse(List<KakaoImageDocument> documents) {
	}

	private record KakaoImageDocument(String image_url, String thumbnail_url) {
		private String imageUrl() {
			return thumbnail_url == null || thumbnail_url.isBlank() ? image_url : thumbnail_url;
		}
	}
}
