package com.buyeoon.place.sync;

import java.util.List;
import java.util.Map;

/**
 * TourAPI(한국관광공사) 호출 경계. 외부 시스템이므로 통합 테스트에서는 Mock으로 대체한다.
 */
public interface TourApiClient {

	/** areaBasedList2로 부여 지역 콘텐츠 목록(contentId·분류)을 수집한다. */
	List<TourApiAreaItem> fetchAreaItems();

	/** detailCommon2 + detailIntro2를 합쳐 항목 하나의 상세를 조회한다. */
	TourApiPlaceDetail fetchPlaceDetail(TourApiAreaItem item);

	/** detailInfo2로 이용안내(항목명 -> 내용)를 조회한다. 내용이 빈 항목은 제외되며, 없으면 빈 맵이다. */
	Map<String, String> fetchPlaceInfo(TourApiAreaItem item);
}
