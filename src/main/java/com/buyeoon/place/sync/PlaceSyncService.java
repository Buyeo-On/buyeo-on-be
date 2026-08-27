package com.buyeoon.place.sync;

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

	public PlaceSyncService(TourApiClient tourApiClient, KakaoImageSearchClient kakaoImageSearchClient,
			PlaceUpsertService placeUpsertService) {
		this.tourApiClient = tourApiClient;
		this.kakaoImageSearchClient = kakaoImageSearchClient;
		this.placeUpsertService = placeUpsertService;
	}

	public PlaceSyncResult sync() {
		List<TourApiAreaItem> items = tourApiClient.fetchAreaItems();
		int successCount = 0;
		List<String> failedContentIds = new ArrayList<>();

		for (TourApiAreaItem item : items) {
			PlaceCategory category = TourApiCategoryMapper.map(item);
			if (category == null) {
				continue;
			}
			try {
				TourApiPlaceDetail detail = tourApiClient.fetchPlaceDetail(item)
						.withDetailInfo(tourApiClient.fetchPlaceInfo(item));
				if (detail.firstImageUrl() == null || detail.firstImageUrl().isBlank()) {
					detail = detail.withFirstImageUrl(
							kakaoImageSearchClient.findFirstImageUrl(detail.title(), detail.address()).orElse(null));
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
}
