package com.buyeoon.place.sync;

/**
 * areaBasedList2·locationBasedList2 응답 한 건. contentTypeId는 TourAPI 대분류
 * 코드(문화시설=14, 관광지=12, 음식점=39 등)이고, cat3는 음식점 내 카페 여부(A05020900 등)를 가리는 세부 코드다.
 * 좌표는 상세 조회 전에 부여 경계를 판정하기 위해 목록 응답에서 그대로 싣는다.
 */
public record TourApiAreaItem(String contentId, String contentTypeId, String cat3, Double latitude, Double longitude) {

	/** 좌표를 모르는 경우(구형 호출·테스트)를 위한 축약 생성자. */
	public TourApiAreaItem(String contentId, String contentTypeId, String cat3) {
		this(contentId, contentTypeId, cat3, null, null);
	}

	/** 경계 판정이 가능한 좌표를 가졌는지. */
	boolean hasCoordinates() {
		return latitude != null && longitude != null;
	}
}
