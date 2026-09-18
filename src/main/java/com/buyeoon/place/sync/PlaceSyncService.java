package com.buyeoon.place.sync;

import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.place.entity.PlaceCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlaceSyncService {

	private static final Logger log = LoggerFactory.getLogger(PlaceSyncService.class);

	private final TourApiClient tourApiClient;
	private final PlaceUpsertService placeUpsertService;
	private final BuyeoBoundary buyeoBoundary;

	public PlaceSyncService(TourApiClient tourApiClient, PlaceUpsertService placeUpsertService,
			BuyeoBoundary buyeoBoundary) {
		this.tourApiClient = tourApiClient;
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
						.withDetailInfo(mergeInfo(tourApiClient.fetchPlaceInfo(item),
								tourApiClient.fetchAccessibility(item)));
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
	 * 이용안내 뒤에 무장애 정보를 이어 붙인다. 순서를 지켜 이용안내가 먼저 보이게 하고, 이용안내에 같은 키가 있으면 원본을 남긴다
	 * (무장애 키는 접두사가 있어 실제로 겹치지는 않는다).
	 */
	private static Map<String, String> mergeInfo(Map<String, String> info, Map<String, String> accessibility) {
		if (accessibility == null || accessibility.isEmpty()) {
			return info;
		}
		Map<String, String> merged = new LinkedHashMap<>(info);
		accessibility.forEach(merged::putIfAbsent);
		return merged;
	}

	/**
	 * 위치기반 조회는 반경 안에 청양·논산 등 인접 시군을 함께 돌려주므로 부여 경계 밖 항목을 상세 조회 전에 걸러 호출을 아낀다. 좌표를
	 * 모르는 항목은 판정할 수 없으므로 통과시키고 이후 단계에 맡긴다.
	 */
	private boolean outsideBuyeo(TourApiAreaItem item) {
		return item.hasCoordinates() && !buyeoBoundary.covers(item.latitude(), item.longitude());
	}
}
