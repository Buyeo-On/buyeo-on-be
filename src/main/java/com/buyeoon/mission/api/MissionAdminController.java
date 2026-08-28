package com.buyeoon.mission.api;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.mission.application.MissionAdminCommandService;
import com.buyeoon.mission.application.MissionAdminQueryService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MissionAdminController {

	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 100;

	private final MissionAdminCommandService missionAdminCommandService;
	private final MissionAdminQueryService missionAdminQueryService;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionAdminController(MissionAdminCommandService missionAdminCommandService,
			MissionAdminQueryService missionAdminQueryService) {
		this.missionAdminCommandService = missionAdminCommandService;
		this.missionAdminQueryService = missionAdminQueryService;
	}

	@PostMapping("/admin/missions")
	public SuccessResponse<Map<String, UUID>> create(@RequestBody MissionAdminCreateRequest request) {
		UUID missionId = missionAdminCommandService.create(request);
		return SuccessResponse.of(Map.of("missionId", missionId));
	}

	@PutMapping("/admin/missions/{missionId}")
	public SuccessResponse<Map<String, Object>> update(@PathVariable UUID missionId,
			@RequestBody MissionAdminUpdateRequest request) {
		missionAdminCommandService.update(missionId, request);
		return SuccessResponse.of(Map.of());
	}

	@DeleteMapping("/admin/missions/{missionId}")
	public SuccessResponse<Map<String, Object>> delete(@PathVariable UUID missionId) {
		missionAdminCommandService.delete(missionId);
		return SuccessResponse.of(Map.of());
	}

	@PostMapping("/admin/missions/{missionId}/restore")
	public SuccessResponse<Map<String, Object>> restore(@PathVariable UUID missionId) {
		missionAdminCommandService.restore(missionId);
		return SuccessResponse.of(Map.of());
	}

	@GetMapping("/admin/missions")
	public SuccessResponse<MissionAdminListView> list(@RequestParam(required = false) UUID placeId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		if (size < MIN_SIZE || size > MAX_SIZE || page < 0) {
			throw new InvalidMissionRequestException();
		}
		return SuccessResponse.of(missionAdminQueryService.list(placeId, page, size));
	}

	@GetMapping("/admin/missions/{missionId}")
	public SuccessResponse<MissionAdminView> get(@PathVariable UUID missionId) {
		return SuccessResponse.of(missionAdminQueryService.get(missionId));
	}
}
