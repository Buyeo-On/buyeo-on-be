package com.buyeoon.notification.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code fcm.enabled} 값에 따라 실제 Firebase 구현이나 no-op 구현을 {@link FcmClient} 빈으로
 * 등록한다.
 */
@Configuration
public class FcmClientConfiguration {

	/**
	 * Firebase Admin Java SDK와 Application Default Credentials로 실제 FCM 클라이언트를 구성한다.
	 */
	@Configuration
	@ConditionalOnProperty(prefix = "fcm", name = "enabled", havingValue = "true")
	static class EnabledFcmClientConfiguration {

		@Bean
		FirebaseApp firebaseApp() throws IOException {
			FirebaseOptions options = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.getApplicationDefault()).build();
			return FirebaseApp.initializeApp(options);
		}

		@Bean
		FcmClient fcmClient(FirebaseApp firebaseApp) {
			return new FirebaseFcmClient(FirebaseMessaging.getInstance(firebaseApp));
		}
	}

	/** FCM이 비활성화된 기본 구성에서는 Firebase 자격증명 없이도 기동할 수 있도록 no-op 구현을 등록한다. */
	@Bean
	@ConditionalOnProperty(prefix = "fcm", name = "enabled", havingValue = "false", matchIfMissing = true)
	FcmClient noOpFcmClient() {
		return new NoOpFcmClient();
	}
}
