package com.buyeoon.place.sync;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TourAPI detailIntro2의 usetime은 자유텍스트라 형식이 일정하지 않다. "HH:mm~HH:mm" 계열의 흔한 패턴만
 * 구조화하고, 그 외는 파싱 실패로 취급해 원문만 보존한다.
 */
final class OperatingHoursParser {

	private static final Pattern RANGE_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})\\s*[~\\-]\\s*(\\d{1,2}:\\d{2})");
	private static final Pattern FEE_DIGITS_PATTERN = Pattern.compile("([\\d,]+)\\s*원");
	private static final Pattern ALWAYS_OPEN_PATTERN = Pattern.compile("상시\\s*개방|연중\\s*무휴");
	private static final Pattern FREE_PATTERN = Pattern.compile("무\\s*료|입장\\s*료\\s*(없음|없다|면제)");

	private OperatingHoursParser() {
	}

	static ParsedOperatingHours parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return new ParsedOperatingHours(false, null, null);
		}
		if (ALWAYS_OPEN_PATTERN.matcher(raw).find()) {
			return new ParsedOperatingHours(true, null, null);
		}
		Matcher matcher = RANGE_PATTERN.matcher(raw);
		if (!matcher.find()) {
			return new ParsedOperatingHours(false, null, null);
		}
		try {
			LocalTime opensAt = LocalTime.parse(normalize(matcher.group(1)));
			LocalTime closesAt = LocalTime.parse(normalize(matcher.group(2)));
			return new ParsedOperatingHours(false, opensAt, closesAt);
		} catch (DateTimeParseException exception) {
			return new ParsedOperatingHours(false, null, null);
		}
	}

	static Integer parseFee(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		if (FREE_PATTERN.matcher(raw).find()) {
			return 0;
		}
		Matcher matcher = FEE_DIGITS_PATTERN.matcher(raw);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Integer.parseInt(matcher.group(1).replace(",", ""));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String normalize(String hhmm) {
		return hhmm.length() == 4 ? "0" + hhmm : hhmm;
	}

	record ParsedOperatingHours(boolean alwaysOpen, LocalTime opensAt, LocalTime closesAt) {
	}
}
