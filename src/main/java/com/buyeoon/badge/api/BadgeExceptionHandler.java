package com.buyeoon.badge.api;

import com.buyeoon.common.api.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BadgeController.class)
public class BadgeExceptionHandler {

	@ExceptionHandler(InvalidBadgeRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}
}
