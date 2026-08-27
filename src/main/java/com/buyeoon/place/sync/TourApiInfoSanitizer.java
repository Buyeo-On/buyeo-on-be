package com.buyeoon.place.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * detailInfo2 응답의 표기 흔들림을 정리한다. TourAPI는 항목명을 "입 장 료"처럼 한 글자씩 띄어 보내기도
 * 하고, 본문에 {@code <br>}·{@code <br />}를 섞어 보내기도 한다. 내용이 빈 항목은 보관하지 않는다.
 */
final class TourApiInfoSanitizer {

	private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");

	private static final Pattern REPEATED_NEWLINES = Pattern.compile("\\n{2,}");

	private TourApiInfoSanitizer() {
	}

	/** 항목명 -> 내용. 내용이 빈 항목과 이름이 없는 항목은 제외하며, 응답 순서를 유지한다. */
	static Map<String, String> sanitize(List<TourApiInfoItem> items) {
		Map<String, String> sanitized = new LinkedHashMap<>();
		if (items == null) {
			return sanitized;
		}
		for (TourApiInfoItem item : items) {
			String name = normalizeName(item.infoname());
			String text = normalizeText(item.infotext());
			if (name.isEmpty() || text.isEmpty()) {
				continue;
			}
			sanitized.putIfAbsent(name, text);
		}
		return sanitized;
	}

	/** 모든 토큰이 한 글자인 항목명("입 장 료")만 붙인다. "내국인 예약안내"처럼 단어가 섞인 이름은 그대로 둔다. */
	private static String normalizeName(String raw) {
		if (raw == null) {
			return "";
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		String[] tokens = trimmed.split("\\s+");
		if (tokens.length < 2) {
			return trimmed;
		}
		for (String token : tokens) {
			if (token.length() > 1) {
				return trimmed;
			}
		}
		return String.join("", tokens);
	}

	private static String normalizeText(String raw) {
		if (raw == null) {
			return "";
		}
		String withBreaks = LINE_BREAK_TAG.matcher(raw).replaceAll("\n");
		return REPEATED_NEWLINES.matcher(withBreaks).replaceAll("\n").trim();
	}
}
