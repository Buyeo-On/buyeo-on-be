package com.buyeoon.place.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TourApiInfoSanitizerTests {

	@Test
	@DisplayName("한 글자씩 띄어 온 항목명은 붙인다")
	void joinsSingleCharacterSpacedNames() {
		assertThat(sanitize("입 장 료", "무료")).containsExactly(entry("입장료", "무료"));
	}

	@Test
	@DisplayName("단어가 섞인 항목명은 공백을 유지한다")
	void keepsSpacesInMultiWordNames() {
		assertThat(sanitize("내국인 예약안내", "전화 예약")).containsExactly(entry("내국인 예약안내", "전화 예약"));
	}

	@Test
	@DisplayName("내용이 빈 항목은 제외한다")
	void dropsItemsWithoutText() {
		assertThat(TourApiInfoSanitizer.sanitize(List.of(new TourApiInfoItem("등산로", ""),
				new TourApiInfoItem("화장실", "  "), new TourApiInfoItem("입 장 료", "무료"))))
				.containsExactly(entry("입장료", "무료"));
	}

	@Test
	@DisplayName("br 태그는 개행으로 바꾸고 중복 개행은 합친다")
	void replacesLineBreakTags() {
		assertThat(sanitize("이용가능시설", "[2층] <br>- 전시존<br />\n[3층] <br>- 체험존"))
				.containsExactly(entry("이용가능시설", "[2층] \n- 전시존\n[3층] \n- 체험존"));
	}

	@Test
	@DisplayName("같은 항목명이 반복되면 첫 값을 유지한다")
	void keepsFirstValueForDuplicateNames() {
		assertThat(TourApiInfoSanitizer
				.sanitize(List.of(new TourApiInfoItem("화장실", "있음"), new TourApiInfoItem("화장실", "없음"))))
				.containsExactly(entry("화장실", "있음"));
	}

	@Test
	@DisplayName("응답이 없으면 빈 맵을 돌려준다")
	void returnsEmptyMapForNullItems() {
		assertThat(TourApiInfoSanitizer.sanitize(null)).isEmpty();
	}

	private static java.util.Map<String, String> sanitize(String name, String text) {
		return TourApiInfoSanitizer.sanitize(List.of(new TourApiInfoItem(name, text)));
	}

	private static java.util.Map.Entry<String, String> entry(String key, String value) {
		return java.util.Map.entry(key, value);
	}
}
