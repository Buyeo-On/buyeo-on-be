package com.buyeoon.member.application;

import com.buyeoon.member.api.InvalidProfileRequestException;
import com.buyeoon.member.application.MemberQueryService.MemberView;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ProfileUpdateService {

	private final JdbcOperations jdbcOperations;
	private final MemberQueryService memberQueryService;
	private final TransactionTemplate transactions;

	public ProfileUpdateService(JdbcOperations jdbcOperations, MemberQueryService memberQueryService,
			PlatformTransactionManager transactionManager) {
		this.jdbcOperations = jdbcOperations;
		this.memberQueryService = memberQueryService;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	public MemberView update(UUID memberId, ProfileUpdateCommand command) {
		return transactions.execute(status -> updateInTransaction(memberId, command));
	}

	private MemberView updateInTransaction(UUID memberId, ProfileUpdateCommand command) {
		lockActiveMember(memberId);
		StoredProfile current = currentIssuedProfile(memberId);
		String displayName = command.displayName() == null ? current.displayName() : command.displayName();
		UUID characterId = command.characterId() == null ? current.characterId() : command.characterId();
		UUID themeId = command.themeId() == null ? current.themeId() : command.themeId();
		if (command.characterId() != null && !characterExists(characterId)) {
			throw new InvalidProfileRequestException();
		}
		if (command.themeId() != null && !themeExists(themeId)) {
			throw new InvalidProfileRequestException();
		}
		if (!displayName.equals(current.displayName()) || !characterId.equals(current.characterId())) {
			int updated = jdbcOperations.update("""
					UPDATE member_profiles
					SET display_name = ?, character_id = ?, updated_at = CURRENT_TIMESTAMP
					WHERE member_id = ?
					""", displayName, characterId, memberId);
			if (updated != 1) {
				throw new IllegalStateException("프로필을 변경할 수 없습니다.");
			}
		}
		if (!themeId.equals(current.themeId())) {
			int updated = jdbcOperations.update("UPDATE citizen_cards SET theme_id = ? WHERE member_id = ?", themeId,
					memberId);
			if (updated != 1) {
				throw new IllegalStateException("군민증 테마를 변경할 수 없습니다.");
			}
		}
		return memberQueryService.getActiveMember(memberId);
	}

	private void lockActiveMember(UUID memberId) {
		boolean memberExists = !jdbcOperations.query("""
				SELECT id
				FROM members
				WHERE id = ? AND status = 'ACTIVE'
				FOR UPDATE
				""", (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), memberId).isEmpty();
		if (!memberExists) {
			throw new AuthenticationCredentialsNotFoundException("활성 회원이 아닙니다.");
		}
	}

	private StoredProfile currentIssuedProfile(UUID memberId) {
		return jdbcOperations.query("""
				SELECT profile.display_name, profile.character_id, card.theme_id
				FROM member_profiles profile
				JOIN citizen_cards card ON card.member_id = profile.member_id
				WHERE profile.member_id = ?
				FOR UPDATE OF profile, card
				""", (resultSet, rowNumber) -> {
			UUID characterId = resultSet.getObject("character_id", UUID.class);
			UUID themeId = resultSet.getObject("theme_id", UUID.class);
			return new StoredProfile(resultSet.getString("display_name"), characterId, themeId);
		}, memberId).stream().findFirst().orElseThrow(InvalidStateTransitionException::new);
	}

	private boolean characterExists(UUID characterId) {
		return Boolean.TRUE.equals(jdbcOperations.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM card_characters WHERE id = ?)", Boolean.class, characterId));
	}

	private boolean themeExists(UUID themeId) {
		return Boolean.TRUE.equals(jdbcOperations
				.queryForObject("SELECT EXISTS (SELECT 1 FROM card_themes WHERE id = ?)", Boolean.class, themeId));
	}

	public record ProfileUpdateCommand(String displayName, UUID characterId, UUID themeId) {
	}

	private record StoredProfile(String displayName, UUID characterId, UUID themeId) {
	}
}
