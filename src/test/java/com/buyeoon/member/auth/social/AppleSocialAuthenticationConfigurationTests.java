package com.buyeoon.member.auth.social;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppleSocialAuthenticationConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(AppleSocialAuthenticationConfiguration.class);

	@Test
	@DisplayName("Apple 로그인이 비활성화되면 Apple 설정 없이 애플리케이션 구성을 만들 수 있다")
	void disabledAppleLoginDoesNotRequireAppleConfiguration() {
		contextRunner.withPropertyValues("social.apple.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean("appleSocialCredentialVerifier");
		});
	}
}
