package com.buyeoon.member.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	private static final OAuth2Error INVALID_TOKEN = new OAuth2Error("invalid_token", "유효하지 않은 인증입니다.", null);

	@Bean
	SecretKey jwtSecretKey(@Value("${security.jwt.secret-base64}") String encodedSecret) {
		byte[] secret = Base64.getDecoder().decode(encodedSecret);
		if (secret.length < 32) {
			throw new IllegalArgumentException("JWT_SECRET은 256비트 이상이어야 합니다.");
		}
		return new SecretKeySpec(secret, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey secretKey) {
		return NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey secretKey, JdbcTemplate jdbcTemplate) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
				activeSessionValidator(jdbcTemplate)));
		return decoder;
	}

	@Bean
	AuthenticationEntryPoint authenticationEntryPoint() {
		return (request, response, exception) -> {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter()
					.write("{\"success\":false,\"data\":{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}}");
		};
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/auth/social-login", "/auth/refresh", "/terms", "/actuator/health")
						.permitAll().anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
				.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(entryPoint))
				.build();
	}

	private OAuth2TokenValidator<Jwt> activeSessionValidator(JdbcTemplate jdbcTemplate) {
		return jwt -> {
			try {
				String subject = jwt.getSubject();
				String sessionClaim = jwt.getClaimAsString("sid");
				if (subject == null || sessionClaim == null) {
					return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
				}
				UUID memberId = UUID.fromString(subject);
				UUID sessionId = UUID.fromString(sessionClaim);
				Boolean active = jdbcTemplate.queryForObject("""
						SELECT EXISTS (
						    SELECT 1
						    FROM auth_sessions session
						    JOIN members member ON member.id = session.member_id
						    WHERE session.id = ?
						      AND session.member_id = ?
						      AND session.expires_at > CURRENT_TIMESTAMP
						      AND session.revoked_at IS NULL
						      AND member.status = 'ACTIVE'
						)
						""", Boolean.class, sessionId, memberId);
				return Boolean.TRUE.equals(active)
						? OAuth2TokenValidatorResult.success()
						: OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
			} catch (IllegalArgumentException exception) {
				return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
			}
		};
	}
}
