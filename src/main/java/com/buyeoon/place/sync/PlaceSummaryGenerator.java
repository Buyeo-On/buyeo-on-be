package com.buyeoon.place.sync;

import java.util.regex.Pattern;

final class PlaceSummaryGenerator {

	private static final int MAX_LENGTH = 36;
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern SENTENCE_END = Pattern.compile("[.!?。]");

	private PlaceSummaryGenerator() {
	}

	static String fromOverview(String overview) {
		if (overview == null || overview.isBlank()) {
			return null;
		}
		String normalized = HTML_TAG.matcher(overview).replaceAll(" ").replace("&nbsp;", " ");
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
		if (normalized.isEmpty()) {
			return null;
		}
		var sentenceEnd = SENTENCE_END.matcher(normalized);
		if (sentenceEnd.find()) {
			normalized = normalized.substring(0, sentenceEnd.start()).trim();
		}

		int length = normalized.codePointCount(0, normalized.length());
		if (length <= MAX_LENGTH) {
			return normalized;
		}
		int endIndex = normalized.offsetByCodePoints(0, MAX_LENGTH);
		return normalized.substring(0, endIndex).trim();
	}
}
