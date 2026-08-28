package com.buyeoon.place.sync;

import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.place.entity.PlaceCategory;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlaceSyncService {

	private static final Logger log = LoggerFactory.getLogger(PlaceSyncService.class);

	private final TourApiClient tourApiClient;
	private final KakaoImageSearchClient kakaoImageSearchClient;
	private final PlaceUpsertService placeUpsertService;
	private final BuyeoBoundary buyeoBoundary;

	public PlaceSyncService(TourApiClient tourApiClient, KakaoImageSearchClient kakaoImageSearchClient,
			PlaceUpsertService placeUpsertService, BuyeoBoundary buyeoBoundary) {
		this.tourApiClient = tourApiClient;
		this.kakaoImageSearchClient = kakaoImageSearchClient;
		this.placeUpsertService = placeUpsertService;
		this.buyeoBoundary = buyeoBoundary;
	}

	public PlaceSyncResult sync() {
		List<TourApiAreaItem> items = tourApiClient.fetchAreaItems();
		int successCount = 0;
		List<String> failedContentIds = new ArrayList<>();

		for (TourApiAreaItem item : items) {
			PlaceCategory category = TourApiCategoryMapper.map(item);
			if (category == null || outsideBuyeo(item)) {
				continue;
			}
			try {
				TourApiPlaceDetail detail = tourApiClient.fetchPlaceDetail(item)
						.withDetailInfo(tourApiClient.fetchPlaceInfo(item));
				if (detail.firstImageUrl() == null || detail.firstImageUrl().isBlank()) {
					String fallbackImageUrl = kakaoImageSearchClient.findFirstImageUrl(detail.title(), detail.address())
							.orElse(null);
					detail = detail.withFirstImageUrl(fallbackImageUrl);
				}
				placeUpsertService.upsert(category, detail);
				successCount++;
			} catch (RuntimeException exception) {
				log.warn("TourAPI 장소 동기화 실패: contentId={}", item.contentId(), exception);
				failedContentIds.add(item.contentId());
			}
		}

		return new PlaceSyncResult(successCount, failedContentIds.size(), failedContentIds);
	}

	/**
	 * 위치기반 조회는 반경 안에 청양·논산 등 인접 시군을 함께 돌려주므로 부여 경계 밖 항목을 상세 조회 전에 걸러 호출을 아낀다. 좌표를
	 * 모르는 항목은 판정할 수 없으므로 통과시키고 이후 단계에 맡긴다.
	 */
	private boolean outsideBuyeo(TourApiAreaItem item) {
		return item.hasCoordinates() && !buyeoBoundary.covers(item.latitude(), item.longitude());
	}
}
