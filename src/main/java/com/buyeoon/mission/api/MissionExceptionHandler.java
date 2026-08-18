package com.buyeoon.mission.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.mission.application.MissionNotFoundException;
import com.buyeoon.mission.application.TripNotFoundException;
import com.buyeoon.mission.application.TripNotInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = MissionController.class)
public class MissionExceptionHandler {

	@ExceptionHandler({InvalidMissionRequestException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler({TripNotFoundException.class, MissionNotFoundException.class})
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleResourceNotFound() {
		return ErrorResponse.resourceNotFound();
	}

	@ExceptionHandler(TripNotInProgressException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleTripNotInProgress() {
		return ErrorResponse.tripNotInProgress();
	}
}
