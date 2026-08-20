package com.buyeoon.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "social.apple.enabled=false")
@AutoConfigureMockMvc
class DisabledAppleSocialLoginIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Apple 로그인이 비활성화되면 Apple 자격증명 없이 기동하고 Apple 요청을 거부한다")
	void disabledAppleLoginRejectsAppleRequest() throws Exception {
		mockMvc.perform(post("/auth/social-login").contentType(MediaType.APPLICATION_JSON).content("""
				{"provider":"APPLE","authorizationCode":"code","identityToken":"token","nonce":"nonce"}
				""")).andExpect(status().isUnauthorized()).andExpect(content().json("""
				{"success":false,"data":{"code":"SOCIAL_AUTHENTICATION_FAILED","message":"소셜 인증에 실패했습니다."}}
				"""));
	}
}
