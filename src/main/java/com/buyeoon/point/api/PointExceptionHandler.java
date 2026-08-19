package com.buyeoon.point.api;

import com.buyeoon.common.api.ErrorResponse;
import org.springframework.http.HttpStatus;
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
}
