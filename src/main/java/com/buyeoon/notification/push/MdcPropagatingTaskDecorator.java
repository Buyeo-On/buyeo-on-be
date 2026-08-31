package com.buyeoon.notification.push;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * push 발송 executor에 작업을 제출한 시점의 MDC(request_id, cf_ray 등)를 캡처해 워커 스레드에서 복원한다.
 * 워커 스레드는 여러 요청의 작업을 번갈아 처리하므로, 작업이 끝나면 그 스레드가 실행 전에 가지고 있던 MDC로 되돌린다.
 */
final class MdcPropagatingTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		Map<String, String> submitterContext = MDC.getCopyOfContextMap();
		return () -> {
			Map<String, String> workerContext = MDC.getCopyOfContextMap();
			setContext(submitterContext);
			try {
				runnable.run();
			} finally {
				setContext(workerContext);
			}
		};
	}

	private void setContext(Map<String, String> context) {
		if (context == null) {
			MDC.clear();
		} else {
			MDC.setContextMap(context);
		}
	}
}
