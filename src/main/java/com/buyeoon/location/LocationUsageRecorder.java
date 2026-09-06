package com.buyeoon.location;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public final class LocationUsageRecorder {

	private final JdbcOperations jdbcOperations;

	public LocationUsageRecorder(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	public void record(UUID memberId, LocationUsagePurpose purpose) {
		jdbcOperations.update("""
				INSERT INTO location_usage_records (member_id, purpose, used_at, expires_at)
				SELECT ?, ?, usage_time, usage_time + INTERVAL '6 months'
				FROM (SELECT clock_timestamp() AS usage_time) clock
				""", memberId, purpose.name());
	}
}
