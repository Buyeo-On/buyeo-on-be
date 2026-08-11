package com.buyeoon.common.api;

public record SuccessResponse<T>(boolean success, T data) {

	public static <T> SuccessResponse<T> of(T data) {
		return new SuccessResponse<>(true, data);
	}
}
