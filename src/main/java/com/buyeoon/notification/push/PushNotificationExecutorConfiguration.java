package com.buyeoon.notification.push;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 업무 트랜잭션과 분리된 FCM 발송 전용 bounded executor를 구성한다. worker 2개, queue 100을 사용하며, 제출
 * 시점의 MDC를 워커 스레드로 전파한다.
 */
@Configuration
public class PushNotificationExecutorConfiguration {

	@Bean
	Executor pushNotificationExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("push-notification-");
		executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
		executor.initialize();
		return executor;
	}
}
