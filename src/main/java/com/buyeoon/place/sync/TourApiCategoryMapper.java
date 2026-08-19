package com.buyeoon.place.sync;

import com.buyeoon.place.entity.PlaceCategory;

/**
 * TourAPI contentTypeId(대분류)와 cat3(음식점 세부코드)를 PlaceCategory로 매핑한다. 매핑되지 않는
 * 항목(레포츠, 숙박, 쇼핑 등)은 이번 동기화 범위 밖이라 null을 반환해 건너뛴다.
 */
final class TourApiCategoryMapper {

	private static final String CONTENT_TYPE_TOURIST_SPOT = "12";
	private static final String CONTENT_TYPE_CULTURAL_FACILITY = "14";
	private static final String CONTENT_TYPE_RESTAURANT = "39";
	private static final String CAT3_CAFE_PREFIX = "A05020900";

	private TourApiCategoryMapper() {
	}

	static PlaceCategory map(TourApiAreaItem item) {
		return switch (item.contentTypeId()) {
			case CONTENT_TYPE_TOURIST_SPOT, CONTENT_TYPE_CULTURAL_FACILITY -> PlaceCategory.HERITAGE;
			case CONTENT_TYPE_RESTAURANT ->
				CAT3_CAFE_PREFIX.equals(item.cat3()) ? PlaceCategory.CAFE : PlaceCategory.RESTAURANT;
			default -> null;
		};
	}
}
