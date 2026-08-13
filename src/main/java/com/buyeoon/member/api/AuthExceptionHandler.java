package com.buyeoon.member.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.MemberWithdrawnException;
import com.buyeoon.member.auth.InvalidRefreshTokenException;
import com.buyeoon.member.auth.social.SocialAuthenticationFailedException;
import com.buyeoon.member.auth.social.SocialProviderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

	@ExceptionHandler(InvalidRefreshTokenException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleInvalidRefreshToken() {
		return ErrorResponse.unauthorized();
	}

	@ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleInvalidSession() {
		return ErrorResponse.unauthorized();
	}

	@ExceptionHandler({InvalidSocialLoginRequestException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler(SocialAuthenticationFailedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleSocialAuthenticationFailed() {
		return ErrorResponse.socialAuthenticationFailed();
	}

	@ExceptionHandler(MemberWithdrawnException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleMemberWithdrawn() {
		return ErrorResponse.memberWithdrawn();
	}

	@ExceptionHandler(SocialProviderUnavailableException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public ErrorResponse handleSocialProviderUnavailable() {
		return ErrorResponse.socialProviderUnavailable();
	}
}
