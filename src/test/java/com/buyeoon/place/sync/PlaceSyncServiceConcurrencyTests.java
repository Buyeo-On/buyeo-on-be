package com.buyeoon.place.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.common.location.BuyeoBoundary;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 동기화의 TourAPI 조회가 실제로 병렬로 도는지 검증한다.
 *
 * <p>순차 실행이면 118곳 × 4회 × 90ms가 40초를 넘어 Nginx proxy_read_timeout 30s에 걸린다
 * (2026-08-29 운영 504 3회). 조회를 동시에 돌려 그 시간을 줄이는 것이 이 구조의 목적이므로, 동시성이 사라지면
 * 회귀로 잡아야 한다.
 */
class PlaceSyncServiceConcurrencyTests {

	private static final int WORKERS = 4;

	private ThreadPoolTaskExecutor executor;

	@AfterEach
	void shutdown() {
		if (executor != null) {
			executor.shutdown();
		}
	}

	/**
	 * worker 수만큼의 조회가 서로를 기다리지 않고 동시에 진행되는지 본다. 순차 실행이라면 첫 조회가 latch에서 영원히 막혀
	 * 타임아웃으로 실패한다.
	 */
	@Test
	@DisplayName("장소 상세 조회를 worker 수만큼 동시에 수행한다")
	void fetchesDetailsConcurrently() throws Exception {
		executor = executor(WORKERS);
		CountDownLatch concurrent = new CountDownLatch(WORKERS);
		AtomicInteger peak = new AtomicInteger();
		AtomicInteger running = new AtomicInteger();

		TourApiClient client = new ConcurrencyProbeClient(WORKERS, concurrent, running, peak);
		PlaceSyncService service = new PlaceSyncService(client, new NoOpUpsertService(), buyeoBoundary(),
				executor);

		PlaceSyncResult result = service.sync();

		assertThat(concurrent.await(5, TimeUnit.SECONDS)).as("worker 수만큼 동시에 조회가 진행되어야 한다").isTrue();
		assertThat(peak.get()).isGreaterThan(1);
		assertThat(result.successCount()).isEqualTo(WORKERS);
		assertThat(result.failedContentIds()).isEmpty();
	}

	/** 저장은 호출 스레드에서 순차로 이뤄져야 REQUIRES_NEW 트랜잭션 의미와 커넥션 사용량이 유지된다. */
	@Test
	@DisplayName("저장은 호출 스레드에서 순차로 수행한다")
	void upsertsOnCallingThread() {
		executor = executor(WORKERS);
		RecordingUpsertService upsertService = new RecordingUpsertService();
		TourApiClient client = new SimpleClient(WORKERS);

		new PlaceSyncService(client, upsertService, buyeoBoundary(), executor).sync();

		assertThat(upsertService.threadNames).hasSize(WORKERS)
				.allMatch(name -> name.equals(Thread.currentThread().getName()));
	}

	private static ThreadPoolTaskExecutor executor(int workers) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(workers);
		executor.setMaxPoolSize(workers);
		executor.setQueueCapacity(100);
		executor.initialize();
		return executor;
	}

	/** 실제 부여 경계. 테스트 항목 좌표(정림사지)는 경계 안이라 상세 조회까지 진행된다. */
	private static BuyeoBoundary buyeoBoundary() {
		return new BuyeoBoundary(new ClassPathResource("boundaries/buyeo-44760.geojson"), new ObjectMapper());
	}

	private static TourApiAreaItem item(int index) {
		return new TourApiAreaItem("100" + index, "12", null, 36.2798, 126.9137);
	}

	private static TourApiPlaceDetail detail(TourApiAreaItem item) {
		return new TourApiPlaceDetail(item.contentId(), "장소 " + item.contentId(), "설명", "주소", null, null, 36.2798,
				126.9137, null, null, Map.of());
	}

	/** 조회 안에서 동시 진입 수를 세고, worker 수만큼 모일 때까지 서로 기다린다. */
	private static final class ConcurrencyProbeClient implements TourApiClient {

		private final int count;
		private final CountDownLatch concurrent;
		private final AtomicInteger running;
		private final AtomicInteger peak;

		private ConcurrencyProbeClient(int count, CountDownLatch concurrent, AtomicInteger running,
				AtomicInteger peak) {
			this.count = count;
			this.concurrent = concurrent;
			this.running = running;
			this.peak = peak;
		}

		@Override
		public List<TourApiAreaItem> fetchAreaItems() {
			return java.util.stream.IntStream.range(0, count).mapToObj(PlaceSyncServiceConcurrencyTests::item).toList();
		}

		@Override
		public TourApiPlaceDetail fetchPlaceDetail(TourApiAreaItem item) {
			peak.accumulateAndGet(running.incrementAndGet(), Math::max);
			concurrent.countDown();
			try {
				// 순차 실행이면 여기서 서로를 기다리다 타임아웃된다.
				if (!concurrent.await(3, TimeUnit.SECONDS)) {
					throw new IllegalStateException("동시 실행되지 않았습니다");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			} finally {
				running.decrementAndGet();
			}
			return detail(item);
		}

		@Override
		public Map<String, String> fetchPlaceInfo(TourApiAreaItem item) {
			return Map.of();
		}

		@Override
		public Map<String, String> fetchAccessibility(TourApiAreaItem item) {
			return Map.of();
		}
	}

	/** 지연 없이 항목만 돌려주는 클라이언트. */
	private static final class SimpleClient implements TourApiClient {

		private final int count;

		private SimpleClient(int count) {
			this.count = count;
		}

		@Override
		public List<TourApiAreaItem> fetchAreaItems() {
			return java.util.stream.IntStream.range(0, count).mapToObj(PlaceSyncServiceConcurrencyTests::item).toList();
		}

		@Override
		public TourApiPlaceDetail fetchPlaceDetail(TourApiAreaItem item) {
			return detail(item);
		}

		@Override
		public Map<String, String> fetchPlaceInfo(TourApiAreaItem item) {
			return Map.of();
		}

		@Override
		public Map<String, String> fetchAccessibility(TourApiAreaItem item) {
			return Map.of();
		}
	}

	private static class NoOpUpsertService extends PlaceUpsertService {

		NoOpUpsertService() {
			super(null);
		}

		@Override
		void upsert(com.buyeoon.place.entity.PlaceCategory category, TourApiPlaceDetail detail) {
			// 저장은 이 테스트의 관심사가 아니다.
		}
	}

	private static final class RecordingUpsertService extends PlaceUpsertService {

		private final List<String> threadNames = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		RecordingUpsertService() {
			super(null);
		}

		@Override
		void upsert(com.buyeoon.place.entity.PlaceCategory category, TourApiPlaceDetail detail) {
			threadNames.add(Thread.currentThread().getName());
		}
	}
}
