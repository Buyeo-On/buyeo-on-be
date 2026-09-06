package com.buyeoon.location;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public final class LocationUsageRecordPurgeService {

	private final JdbcOperations jdbcOperations;

	public LocationUsageRecordPurgeService(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	public int purgeExpired() {
		return jdbcOperations.update("DELETE FROM location_usage_records WHERE expires_at <= clock_timestamp()");
	}
}
