package com.buyeoon.place.sync;

/**
 * areaBasedList2 응답 한 건. contentTypeId는 TourAPI 대분류 코드(문화시설=14, 관광지=12, 음식점=39
 * 등)이고, cat3는 음식점 내 카페 여부(A05020900 등)를 가리는 세부 코드다.
 */
public record TourApiAreaItem(String contentId, String contentTypeId, String cat3) {
}
