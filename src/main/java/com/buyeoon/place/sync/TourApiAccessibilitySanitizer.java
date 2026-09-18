package com.buyeoon.place.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * detailWithTour2(무장애 여행 정보) 응답을 {@code 무장애:} 접두사가 붙은 맵으로 정리한다.
 *
 * <p>접두사를 붙이는 이유는 셋이다. 이용안내(detailInfo2)와 출처를 구분할 수 있고, 항목명이 겹쳐 덮어쓰는 일을 막으며,
 * 클라이언트가 개별 키 이름 대신 접두사만 보고 무장애 정보 유무를 판별할 수 있다.
 *
 * <p>TourAPI는 값 끝에 {@code _무장애 편의시설}처럼 분류 꼬리표를 붙여 보내므로 떼어 낸다.
 */
final class TourApiAccessibilitySanitizer {

	/** 클라이언트가 무장애 항목을 가려내는 기준. 바꾸면 앱의 판별 로직도 함께 고쳐야 한다. */
	static final String PREFIX = "무장애:";

	/** 값 끝에 붙는 "_무장애 편의시설", "_시각장애인 편의시설" 같은 분류 꼬리표. */
	private static final Pattern TRAILING_CATEGORY = Pattern.compile("_[^_]*편의시설\\s*$");

	private TourApiAccessibilitySanitizer() {
	}

	/** 응답 항목 하나를 정제된 맵으로 바꾼다. 항목이 없거나 값이 모두 비면 빈 맵이다. */
	static Map<String, String> sanitize(TourApiRestClient.TourApiWithItem item) {
		Map<String, String> sanitized = new LinkedHashMap<>();
		if (item == null) {
			return sanitized;
		}
		put(sanitized, "접근로", item.route());
		put(sanitized, "휠체어", item.wheelchair());
		put(sanitized, "화장실", item.restroom());
		put(sanitized, "점자블록", item.braileblock());
		return sanitized;
	}

	private static void put(Map<String, String> target, String name, String raw) {
		String text = normalize(raw);
		if (!text.isEmpty()) {
			target.put(PREFIX + name, text);
		}
	}

	private static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		return TRAILING_CATEGORY.matcher(raw.trim()).replaceAll("").trim();
	}
}
