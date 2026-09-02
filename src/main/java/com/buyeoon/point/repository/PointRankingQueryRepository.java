package com.buyeoon.point.repository;

import com.buyeoon.point.application.PointRankingCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** 포인트 원장과 현재 회원 프로필을 결합해 UC-29 랭킹 projection을 조회한다. */
@Repository
public class PointRankingQueryRepository {

	private static final String RANKED_MEMBERS = """
			WITH eligible AS (
			    SELECT m.id AS member_id,
			           profile.display_name,
			           character.image_key AS character_image_key,
			           SUM(point.amount)::bigint AS cumulative_earned
			    FROM members m
			    JOIN citizen_cards card ON card.member_id = m.id
			    JOIN member_profiles profile ON profile.member_id = m.id
			    JOIN card_characters character ON character.id = profile.character_id
			    JOIN point_transactions point ON point.member_id = m.id AND point.type = 'EARN'
			    WHERE m.status = 'ACTIVE'
			    GROUP BY m.id, profile.display_name, character.image_key
			    HAVING SUM(point.amount) > 0
			), ranked AS (
			    SELECT member_id,
			           display_name,
			           character_image_key,
			           cumulative_earned,
			           RANK() OVER (ORDER BY cumulative_earned DESC)::bigint AS rank_value
			    FROM eligible
			)
			""";

	private final JdbcOperations jdbcOperations;

	/** 랭킹 집계에 사용할 JDBC 접근 seam을 주입받는다. */
	public PointRankingQueryRepository(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
	}

	/** 첫 페이지를 누적 적립 내림차순과 회원 ID 오름차순으로 조회한다. */
	public List<PointRankingRow> findFirstPage(int limit) {
		return jdbcOperations.query(RANKED_MEMBERS + """
				SELECT member_id, rank_value, display_name, character_image_key, cumulative_earned
				FROM ranked
				ORDER BY cumulative_earned DESC, member_id
				LIMIT ?
				""", this::mapRow, limit);
	}

	/** 커서 뒤의 참여자를 동일한 정렬 기준으로 이어서 조회한다. */
	public List<PointRankingRow> findAfter(PointRankingCursor cursor, int limit) {
		return jdbcOperations.query(RANKED_MEMBERS + """
				SELECT member_id, rank_value, display_name, character_image_key, cumulative_earned
				FROM ranked
				WHERE cumulative_earned < ?
				   OR (cumulative_earned = ? AND member_id > ?)
				ORDER BY cumulative_earned DESC, member_id
				LIMIT ?
				""", this::mapRow, cursor.cumulativeEarned(), cursor.cumulativeEarned(), cursor.memberId(), limit);
	}

	/** 전체 참여자 수와 현재 회원의 전체 랭킹 기준 정보를 한 행으로 조회한다. */
	public PointRankingSummary findSummary(UUID memberId) {
		return jdbcOperations.queryForObject(RANKED_MEMBERS + """
				SELECT COUNT(*)::bigint AS total_participants,
				       MAX(rank_value) FILTER (WHERE member_id = ?) AS my_rank,
				       MAX(display_name) FILTER (WHERE member_id = ?) AS my_display_name,
				       MAX(character_image_key) FILTER (WHERE member_id = ?) AS my_character_image_key,
				       COALESCE(MAX(cumulative_earned) FILTER (WHERE member_id = ?), 0)::bigint
				           AS my_cumulative_earned
				FROM ranked
				""", this::mapSummary, memberId, memberId, memberId, memberId);
	}

	/** JDBC 결과 한 행을 랭킹 참여 회원 projection으로 변환한다. */
	private PointRankingRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PointRankingRow(resultSet.getObject("member_id", UUID.class), resultSet.getLong("rank_value"),
				resultSet.getString("display_name"), resultSet.getString("character_image_key"),
				resultSet.getLong("cumulative_earned"));
	}

	/** 집계 결과 한 행을 전체 수와 내 랭킹 projection으로 변환한다. */
	private PointRankingSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		Long rank = resultSet.getObject("my_rank", Long.class);
		return new PointRankingSummary(resultSet.getLong("total_participants"), rank,
				resultSet.getString("my_display_name"), resultSet.getString("my_character_image_key"),
				resultSet.getLong("my_cumulative_earned"));
	}
}
