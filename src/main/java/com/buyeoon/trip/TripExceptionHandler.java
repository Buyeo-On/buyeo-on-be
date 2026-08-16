package com.buyeoon.trip;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.OutsideBuyeoException;
import com.buyeoon.member.application.RequiredTermsNotAgreedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TripController.class)
public class TripExceptionHandler {

	@ExceptionHandler({InvalidTripStartRequestException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler(RequiredTermsNotAgreedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleRequiredTermsNotAgreed() {
		return ErrorResponse.requiredTermsNotAgreed();
	}

	@ExceptionHandler(CitizenCardNotIssuedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleCitizenCardNotIssued() {
		return ErrorResponse.citizenCardNotIssued();
	}

	@ExceptionHandler(OutsideBuyeoException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleOutsideBuyeo() {
		return ErrorResponse.outsideBuyeo();
	}

	@ExceptionHandler(InvalidStateTransitionException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleInvalidStateTransition() {
		return ErrorResponse.invalidStateTransition();
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleIdempotencyKeyReused() {
		return ErrorResponse.idempotencyKeyReused();
	}
}
