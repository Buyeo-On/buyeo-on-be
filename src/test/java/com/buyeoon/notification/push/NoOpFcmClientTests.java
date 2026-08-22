package com.buyeoon.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.buyeoon.notification.entity.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoOpFcmClientTests {

	@Test
	@DisplayName("비활성화되어 건너뛴 발송 대상 토큰 수를 metric으로 관측할 수 있다")
	void recordsSkippedTokenCount() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NoOpFcmClient client = new NoOpFcmClient(new PushNotificationMetrics(registry));
		PushMessage message = new PushMessage(NotificationType.BADGE, "제목", "본문", null, null, null);

		FcmSendResult result = client.sendMulticast(List.of("token-1", "token-2"), message);

		assertThat(result.acceptedCount()).isZero();
		assertThat(result.failedCount()).isZero();
		assertThat(result.unregisteredTokens()).isEmpty();
		assertThat(registry.get("push_notification.skipped").counter().count()).isEqualTo(2);
	}
}
