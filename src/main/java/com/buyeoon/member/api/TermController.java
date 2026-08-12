package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.TermConsentService;
import com.buyeoon.member.application.TermConsentService.ConsentDecision;
import com.buyeoon.member.application.TermConsentService.TermConsentResult;
import com.buyeoon.member.application.TermQueryService;
import com.buyeoon.member.application.TermQueryService.TermListView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class TermController {

	private final TermQueryService termQueryService;
	private final TermConsentService termConsentService;

	public TermController(TermQueryService termQueryService, TermConsentService termConsentService) {
		this.termQueryService = termQueryService;
		this.termConsentService = termConsentService;
	}

	@GetMapping("/terms")
	public SuccessResponse<TermListView> getTerms() {
		return SuccessResponse.of(termQueryService.getCurrentTerms());
	}

	@PutMapping("/members/me/term-consents")
	public SuccessResponse<TermConsentResult> updateTermConsents(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody JsonNode request) {
		UUID memberId = UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
		return SuccessResponse.of(termConsentService.update(memberId, idempotencyKey, parseConsents(request)));
	}

	private List<ConsentDecision> parseConsents(JsonNode request) {
		if (request == null || !request.isObject() || !hasOnlyProperties(request, Set.of("consents"))) {
			throw new InvalidTermConsentRequestException();
		}
		JsonNode consents = request.get("consents");
		if (consents == null || !consents.isArray() || consents.size() < 1 || consents.size() > 3) {
			throw new InvalidTermConsentRequestException();
		}

		List<ConsentDecision> decisions = new ArrayList<>(consents.size());
		for (int index = 0; index < consents.size(); index++) {
			JsonNode consent = consents.get(index);
			if (consent == null || !consent.isObject()
					|| !hasOnlyProperties(consent, Set.of("termId", "version", "agreed"))) {
				throw new InvalidTermConsentRequestException();
			}
			JsonNode termId = consent.get("termId");
			JsonNode version = consent.get("version");
			JsonNode agreed = consent.get("agreed");
			if (termId == null || !termId.isString() || version == null || !version.isString()
					|| version.stringValue().isBlank() || agreed == null || !agreed.isBoolean()) {
				throw new InvalidTermConsentRequestException();
			}
			try {
				decisions.add(new ConsentDecision(UUID.fromString(termId.stringValue()), version.stringValue(),
						agreed.booleanValue()));
			} catch (IllegalArgumentException exception) {
				throw new InvalidTermConsentRequestException();
			}
		}
		return decisions;
	}

	private boolean hasOnlyProperties(JsonNode node, Set<String> properties) {
		return node.size() == properties.size()
				&& node.properties().stream().allMatch(property -> properties.contains(property.getKey()));
	}
}
