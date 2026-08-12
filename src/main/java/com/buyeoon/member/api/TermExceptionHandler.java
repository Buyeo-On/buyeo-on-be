package com.buyeoon.member.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.TermVersionOutdatedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TermController.class)
public class TermExceptionHandler {

	@ExceptionHandler({InvalidTermConsentRequestException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler(TermVersionOutdatedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleTermVersionOutdated() {
		return ErrorResponse.termVersionOutdated();
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleIdempotencyKeyReused() {
		return ErrorResponse.idempotencyKeyReused();
	}
}
