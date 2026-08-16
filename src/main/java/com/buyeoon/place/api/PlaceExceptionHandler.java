package com.buyeoon.place.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.place.application.ActiveTripRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PlaceController.class)
public class PlaceExceptionHandler {

	@ExceptionHandler({InvalidPlaceRequestException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler(ActiveTripRequiredException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleActiveTripRequired() {
		return ErrorResponse.activeTripRequired();
	}
}
