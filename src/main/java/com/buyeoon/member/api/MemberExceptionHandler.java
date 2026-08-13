package com.buyeoon.member.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.InvalidStateTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MemberController.class)
public class MemberExceptionHandler {

	@ExceptionHandler({InvalidSettingsRequestException.class, InvalidPushTokenRequestException.class,
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

	@ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleUnauthorized() {
		return ErrorResponse.unauthorized();
	}
}
