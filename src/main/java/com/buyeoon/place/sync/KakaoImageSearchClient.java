package com.buyeoon.place.sync;

import java.util.Optional;
public interface KakaoImageSearchClient {

	Optional<String> findFirstImageUrl(String placeName, String address);
}
