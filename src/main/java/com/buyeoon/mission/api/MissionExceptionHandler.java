package com.buyeoon.mission.api;

import com.buyeoon.common.api.ErrorResponse;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.mission.application.InvalidMissionSubmissionException;
import com.buyeoon.mission.application.MissionNotFoundException;
import com.buyeoon.mission.application.MissionPhotoNotFoundException;
import com.buyeoon.mission.application.MissionPhotoTooLargeException;
import com.buyeoon.mission.application.OutsideParticipationRadiusException;
import com.buyeoon.mission.application.TripNotFoundException;
import com.buyeoon.mission.application.TripNotInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {MissionController.class, MissionAdminController.class})
public class MissionExceptionHandler {

	@ExceptionHandler({InvalidMissionRequestException.class, InvalidMissionSubmissionException.class,
			MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
			HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidRequest() {
		return ErrorResponse.invalidRequest();
	}

	@ExceptionHandler({TripNotFoundException.class, MissionNotFoundException.class,
			MissionPhotoNotFoundException.class})
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleResourceNotFound() {
		return ErrorResponse.resourceNotFound();
	}

	@ExceptionHandler(MissionPhotoTooLargeException.class)
	@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
	public ErrorResponse handlePayloadTooLarge() {
		return ErrorResponse.payloadTooLarge();
	}

	@ExceptionHandler(OutsideParticipationRadiusException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ErrorResponse handleOutsideParticipationRadius() {
		return ErrorResponse.locationVerificationFailed();
	}

	@ExceptionHandler(TripNotInProgressException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ErrorResponse handleTripNotInProgress() {
		return ErrorResponse.tripNotInProgress();
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

	/** 회원 행 잠금 시 활성 상태가 아니면 인증 실패 응답을 반환한다. */
	@ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public ErrorResponse handleUnauthorized() {
		return ErrorResponse.unauthorized();
	}
}
