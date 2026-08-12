package com.buyeoon.member.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.member.application.TermQueryService;
import com.buyeoon.member.application.TermQueryService.TermListView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/terms")
public class TermController {

	private final TermQueryService termQueryService;

	public TermController(TermQueryService termQueryService) {
		this.termQueryService = termQueryService;
	}

	@GetMapping
	public SuccessResponse<TermListView> getTerms() {
		return SuccessResponse.of(termQueryService.getCurrentTerms());
	}
}
