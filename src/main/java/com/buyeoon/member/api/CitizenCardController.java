package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.CitizenCardQueryService;
import com.buyeoon.member.application.CitizenCardQueryService.CitizenCardOptionsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CitizenCardController {

	private final CitizenCardQueryService citizenCards;

	public CitizenCardController(CitizenCardQueryService citizenCards) {
		this.citizenCards = citizenCards;
	}

	@GetMapping("/citizen-cards/options")
	public SuccessResponse<CitizenCardOptionsView> getOptions() {
		return SuccessResponse.of(citizenCards.getOptions());
	}
}
