package com.buyeoon.place.sync;

import com.buyeoon.notification.push.MdcPropagatingTaskDecorator;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 장소 동기화의 TourAPI 조회 전용 bounded executor.
 *
 * <p>동기화 1회는 장소당 상세 조회 4회(detailCommon2, detailIntro2, detailInfo2, detailWithTour2)를
 * 순차로 돌아 118곳 기준 약 475회가 된다. 호출당 약 90ms로 전체 40초를 넘겨 Nginx
 * {@code proxy_read_timeout 30s}에 걸렸다(2026-08-29 운영 504 3회).
 *
 * <p>worker를 4개로 두는 것은 TourAPI의 초당 호출 제한이 공개되어 있지 않아서다. 일일 트래픽은 운영계정 100,000건이라
 * 여유가 있지만 순간 동시성은 보수적으로 잡는다. 조회만 이 풀에서 돌고 DB 저장은 호출 스레드에서 순차로 하므로 커넥션 풀
 * (기본 10)에는 영향이 없다.
 */
@Configuration
public class PlaceSyncExecutorConfiguration {

	@Bean
	Executor placeSyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("place-sync-");
		executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
		executor.initialize();
		return executor;
	}
}
