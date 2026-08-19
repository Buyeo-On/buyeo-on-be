package com.buyeoon.place.sync;

/** detailCommon2 + detailIntro2를 합친 장소 상세. useTime·useFee는 자유텍스트 원문이다. */
public record TourApiPlaceDetail(String contentId, String title, String overview, String address, String firstImageUrl,
		double latitude, double longitude, String useTime, String useFee) {
}
