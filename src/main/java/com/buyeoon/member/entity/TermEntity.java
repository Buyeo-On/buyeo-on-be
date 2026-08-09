package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "terms")
public class TermEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "type", nullable = false, columnDefinition = "term_type")
	private TermType type;

	@Column(name = "version", nullable = false, columnDefinition = "text")
	private String version;

	@Column(name = "required", nullable = false)
	private boolean required;

	@Column(name = "title", nullable = false, columnDefinition = "text")
	private String title;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "effective_at", nullable = false)
	private Instant effectiveAt;

	public static TermEntity create(
			TermType type,
			String version,
			boolean required,
			String title,
			String content,
			Instant effectiveAt) {
		TermEntity term = new TermEntity();
		term.type = type;
		term.version = version;
		term.required = required;
		term.title = title;
		term.content = content;
		term.effectiveAt = effectiveAt;
		return term;
	}
}
