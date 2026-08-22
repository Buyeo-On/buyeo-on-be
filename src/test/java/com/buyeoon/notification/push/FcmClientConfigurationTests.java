package com.buyeoon.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FcmClientConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(FcmClientConfiguration.class);

	@Test
	@DisplayName("FCM_ENABLED=false이면 Firebase 자격증명 없이 no-op 구현으로 정상 기동한다")
	void disabledFcmStartsWithNoOpClient() {
		contextRunner.withPropertyValues("fcm.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FcmClient.class);
			assertThat(context.getBean(FcmClient.class)).isInstanceOf(NoOpFcmClient.class);
			assertThat(context).doesNotHaveBean("firebaseApp");
		});
	}

	@Test
	@DisplayName("FCM_ENABLED 기본값은 false이며 Firebase 설정 없이 기동한다")
	void defaultFcmDisabledStartsWithNoOpClient() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(FcmClient.class)).isInstanceOf(NoOpFcmClient.class);
		});
	}

	@Test
	@DisplayName("FCM_ENABLED=true인데 ADC 자격증명이 없으면 애플리케이션 시작이 실패한다")
	void enabledFcmWithoutCredentialsFailsStartup() {
		Assumptions.assumeFalse(adcCredentialsAvailable(), "이 머신에 ADC 자격증명이 구성되어 있어 자격증명 부재 상황을 재현할 수 없다");

		contextRunner.withPropertyValues("fcm.enabled=true").run(context -> assertThat(context).hasFailed());
	}

	/**
	 * {@link com.google.auth.oauth2.GoogleCredentials#getApplicationDefault()}가
	 * 확인하는 ADC 소스가 이미 있는지 본다.
	 */
	private boolean adcCredentialsAvailable() {
		String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		if (credentialsPath != null && !credentialsPath.isBlank()) {
			return true;
		}
		Path wellKnownPath = Path.of(System.getProperty("user.home"), ".config", "gcloud",
				"application_default_credentials.json");
		return Files.exists(wellKnownPath);
	}
}
