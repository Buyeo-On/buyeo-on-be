package com.buyeoon.place.sync;

import java.util.Map;

/**
 * detailCommon2 + detailIntro2 + detailInfo2를 합친 장소 상세. useTime·useFee는 자유텍스트
 * 원문이고, detailInfo는 detailInfo2 이용안내(항목명 -> 내용)다.
 */
public record TourApiPlaceDetail(String contentId, String title, String overview, String address, String firstImageUrl,
		double latitude, double longitude, String useTime, String useFee, Map<String, String> detailInfo) {

	public TourApiPlaceDetail {
		detailInfo = detailInfo == null ? Map.of() : Map.copyOf(detailInfo);
	}

	TourApiPlaceDetail withFirstImageUrl(String imageUrl) {
		return new TourApiPlaceDetail(contentId, title, overview, address, imageUrl, latitude, longitude, useTime,
				useFee, detailInfo);
	}

	TourApiPlaceDetail withDetailInfo(Map<String, String> info) {
		return new TourApiPlaceDetail(contentId, title, overview, address, firstImageUrl, latitude, longitude, useTime,
				useFee, info);
	}
}
