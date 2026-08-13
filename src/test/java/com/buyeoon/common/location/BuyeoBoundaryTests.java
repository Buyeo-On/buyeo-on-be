package com.buyeoon.common.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class BuyeoBoundaryTests {

	private final BuyeoBoundary boundary = new BuyeoBoundary(new ClassPathResource("boundaries/buyeo-44760.geojson"),
			new ObjectMapper());

	@Test
	void coversBuyeoLocationAndBoundaryButNotDaejeon() {
		assertThat(boundary.covers(36.2750, 126.9090)).isTrue();
		assertThat(boundary.covers(36.3427255, 126.8610656)).isTrue();
		assertThat(boundary.covers(36.3500, 127.3800)).isFalse();
	}

	@Test
	void geoJsonMatchesRecordedSha256() throws Exception {
		byte[] geoJson = new ClassPathResource("boundaries/buyeo-44760.geojson").getContentAsByteArray();
		String metadata = new String(
				new ClassPathResource("boundaries/buyeo-44760.metadata.yaml").getContentAsByteArray(),
				StandardCharsets.UTF_8);
		String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(geoJson));

		assertThat(metadata).contains("output-sha256: " + sha256);
	}
}
