package com.buyeoon.common.storage;

import java.time.Instant;
import java.util.Map;

public record PublicImageUploadUrlView(String imageKey, String uploadUrl, String method, Map<String, String> headers,
		int successStatus, Instant expiresAt) {

	public PublicImageUploadUrlView {
		headers = Map.copyOf(headers);
	}
}
