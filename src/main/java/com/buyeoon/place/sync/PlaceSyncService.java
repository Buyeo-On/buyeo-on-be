package com.buyeoon.place.sync;

import com.buyeoon.common.location.BuyeoBoundary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PlaceSyncService {

	private static final Logger log = LoggerFactory.getLogger(PlaceSyncService.class);

	private final TourApiClient tourApiClient;
	private final PlaceUpsertService placeUpsertService;
	private final BuyeoBoundary buyeoBoundary;
	private final Executor placeSyncExecutor;

	public PlaceSyncService(TourApiClient tourApiClient, PlaceUpsertService placeUpsertService,
			BuyeoBoundary buyeoBoundary, @Qualifier("placeSyncExecutor") Executor placeSyncExecutor) {
		this.tourApiClient = tourApiClient;
		this.placeUpsertService = placeUpsertService;
		this.buyeoBoundary = buyeoBoundary;
		this.placeSyncExecutor = placeSyncExecutor;
	}

	/**
	 * 부여 콘텐츠를 모아 상세를 조회하고 저장한다.
	 *
	 * <p>TourAPI 조회는 {@code placeSyncExecutor}에서 병렬로, 저장은 호출 스레드에서 순차로 한다. 전체 시간의
	 * 대부분이 조회(호출당 약 90ms × 장소당 4회)라 병렬화 이득이 거기 있고, 저장을 순차로 두면
	 * {@link PlaceUpsertService}의 REQUIRES_NEW 트랜잭션 의미와 커넥션 사용량이 그대로 유지된다.
	 *
	 * <p>항목 하나의 실패가 전체를 멈추지 않는 것은 이전과 같다. 조회 단계에서 실패한 항목은 저장 대상에서 빠지고 실패 목록에 남는다.
	 */
	public PlaceSyncResult sync() {
		List<TourApiAreaItem> candidates = tourApiClient.fetchAreaItems().stream()
				.filter(item -> TourApiCategoryMapper.map(item) != null).filter(item -> !outsideBuyeo(item)).toList();

		Map<TourApiAreaItem, CompletableFuture<TourApiPlaceDetail>> fetches = new LinkedHashMap<>();
		for (TourApiAreaItem item : candidates) {
			fetches.put(item, CompletableFuture.supplyAsync(() -> fetchDetail(item), placeSyncExecutor));
		}

		int successCount = 0;
		List<String> failedContentIds = new ArrayList<>();
		for (Map.Entry<TourApiAreaItem, CompletableFuture<TourApiPlaceDetail>> entry : fetches.entrySet()) {
			TourApiAreaItem item = entry.getKey();
			try {
				TourApiPlaceDetail detail = entry.getValue().join();
				placeUpsertService.upsert(TourApiCategoryMapper.map(item), detail);
				successCount++;
			} catch (RuntimeException exception) {
				log.warn("TourAPI 장소 동기화 실패: contentId={}", item.contentId(), unwrap(exception));
				failedContentIds.add(item.contentId());
			}
		}

		return new PlaceSyncResult(successCount, failedContentIds.size(), failedContentIds);
	}

	/** 장소 하나에 필요한 TourAPI 조회를 모아서 수행한다. 워커 스레드에서 돌며 DB는 건드리지 않는다. */
	private TourApiPlaceDetail fetchDetail(TourApiAreaItem item) {
		return tourApiClient.fetchPlaceDetail(item)
				.withDetailInfo(mergeInfo(tourApiClient.fetchPlaceInfo(item), tourApiClient.fetchAccessibility(item)));
	}

	/** join()이 감싼 CompletionException을 벗겨 원래 원인을 로그에 남긴다. */
	private static Throwable unwrap(Throwable exception) {
		return exception instanceof CompletionException && exception.getCause() != null ? exception.getCause()
				: exception;
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
