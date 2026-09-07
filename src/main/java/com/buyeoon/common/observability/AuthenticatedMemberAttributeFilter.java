package com.buyeoon.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증된 회원 ID를 요청 속성에 복사한다.
 *
 * <p>Spring Security 체인 안쪽(가장 낮은 우선순위)에서 돌아 JWT subject를 읽을 수 있다. 바깥의
 * {@link RequestCorrelationFilter}는 체인이 끝난 뒤 로그를 남기는데 그때는 Security 컨텍스트가 이미 비워져 있어,
 * 요청이 끝나도 남는 속성으로 넘긴다.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class AuthenticatedMemberAttributeFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
			request.setAttribute(RequestCorrelationFilter.MEMBER_ID_ATTRIBUTE, jwt.getSubject());
		}
		filterChain.doFilter(request, response);
	}
}
