package com.buyeoon.member.application;

import com.buyeoon.member.entity.TermType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public class TermQueryService {

	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;

	public TermQueryService(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	public TermListView getCurrentTerms() {
		return new TermListView(jdbcOperations.query("""
				SELECT DISTINCT ON (term.type)
				       term.id,
				       term.type::text,
				       term.version,
				       term.required,
				       term.title,
				       term.content,
				       term.effective_at
				FROM terms term
				WHERE term.effective_at <= CURRENT_TIMESTAMP
				ORDER BY term.type, term.effective_at DESC
				""", this::mapTerm));
	}

	private TermView mapTerm(ResultSet resultSet, int rowNumber) throws SQLException {
		return new TermView(resultSet.getObject("id", UUID.class), TermType.valueOf(resultSet.getString("type")),
				resultSet.getString("version"), resultSet.getBoolean("required"), resultSet.getString("title"),
				resultSet.getString("content"), resultSet.getTimestamp("effective_at").toInstant().atZone(ASIA_SEOUL));
	}

	public record TermListView(List<TermView> items) {
		public TermListView {
			items = List.copyOf(items);
		}
	}

	public record TermView(UUID termId, TermType type, String version, boolean required, String title, String content,
			ZonedDateTime effectiveAt) {
	}
}
