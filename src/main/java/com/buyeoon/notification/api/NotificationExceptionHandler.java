package com.buyeoon.notification.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.mission.application.MissionNotFoundException;
import com.buyeoon.mission.application.TripNotFoundException;
import com.buyeoon.mission.application.TripNotInProgressException;
import com.buyeoon.notification.application.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {NotificationController.class, BuyeoEntryEventController.class,
		BuyeoExitEventController.class, SpecialQuizNearbyEventController.class})
public class NotificationExceptionHandler {

	@ExceptionHandler({InvalidNotificationRequestException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler({NotificationNotFoundException.class, TripNotFoundException.class,
			MissionNotFoundException.class})
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound() {
		return ErrorResponse.resourceNotFound();
	}

	@ExceptionHandler(TripNotInProgressException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleTripNotInProgress() {
		return ErrorResponse.tripNotInProgress();
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleIdempotencyKeyReused() {
		return ErrorResponse.idempotencyKeyReused();
	}
}
