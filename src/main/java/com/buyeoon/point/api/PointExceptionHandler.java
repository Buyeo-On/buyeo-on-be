package com.buyeoon.point.api;

import com.buyeoon.common.api.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PointController.class)
public class PointExceptionHandler {

	@ExceptionHandler(InvalidPointRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	/** 회원 행 잠금 시 활성 상태가 아니면 인증 실패 응답을 반환한다. */
	@ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleUnauthorized() {
		return ErrorResponse.unauthorized();
	}
}
