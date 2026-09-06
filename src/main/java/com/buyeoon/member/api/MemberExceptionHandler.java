package com.buyeoon.member.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.AppleReauthenticationRequiredException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.KakaoReauthenticationRequiredException;
import com.buyeoon.member.auth.social.SocialAuthenticationFailedException;
import com.buyeoon.member.auth.social.SocialProviderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberController.class)
public class MemberExceptionHandler {

	@ExceptionHandler({InvalidSettingsRequestException.class, InvalidPushTokenRequestException.class,
			InvalidProfileRequestException.class, InvalidWithdrawalRequestException.class,
			HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler(InvalidStateTransitionException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleInvalidStateTransition() {
		return ErrorResponse.invalidStateTransition();
	}

	@ExceptionHandler(AppleReauthenticationRequiredException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleAppleReauthenticationRequired() {
		return ErrorResponse.appleReauthenticationRequired();
	}

	@ExceptionHandler(KakaoReauthenticationRequiredException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleKakaoReauthenticationRequired() {
		return ErrorResponse.kakaoReauthenticationRequired();
	}

	@ExceptionHandler(SocialAuthenticationFailedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleSocialAuthenticationFailed() {
		return ErrorResponse.socialAuthenticationFailed();
	}

	@ExceptionHandler(SocialProviderUnavailableException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public ErrorResponse handleSocialProviderUnavailable() {
		return ErrorResponse.socialProviderUnavailable();
	}

	@ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleUnauthorized() {
		return ErrorResponse.unauthorized();
	}
}
