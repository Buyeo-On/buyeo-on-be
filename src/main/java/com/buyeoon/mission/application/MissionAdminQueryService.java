package com.buyeoon.mission.application;

import com.buyeoon.mission.api.MissionAdminListView;
import com.buyeoon.mission.api.MissionAdminView;
import com.buyeoon.mission.api.MissionAdminView.MissionAdminChoiceView;
import com.buyeoon.mission.entity.MissionChoiceEntity;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionChoiceRepository;
import com.buyeoon.mission.repository.MissionQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MissionAdminQueryService {

	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionAdminQueryService(MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository) {
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
	}

	public MissionAdminListView list(UUID placeId, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<MissionEntity> result = placeId == null
				? missionQueryRepository.findAll(pageRequest)
				: missionQueryRepository.findByPlaceId(placeId, pageRequest);
		List<MissionAdminView> items = result.getContent().stream().map(mission -> toView(mission, List.of())).toList();
		return new MissionAdminListView(items, page, size, result.getTotalElements(), result.getTotalPages());
	}

	public MissionAdminView get(UUID missionId) {
		MissionEntity mission = missionQueryRepository.findById(missionId).orElseThrow(MissionNotFoundException::new);
		List<MissionChoiceEntity> choices = mission.getType() == MissionType.MULTIPLE_CHOICE
				? missionChoiceRepository.findByMissionIdOrderBySortOrderAsc(missionId)
				: List.of();
		return toView(mission, choices);
	}

	private MissionAdminView toView(MissionEntity mission, List<MissionChoiceEntity> choices) {
		List<MissionAdminChoiceView> choiceViews = choices.stream()
				.map(choice -> new MissionAdminChoiceView(choice.getId(), choice.getLabel(), choice.isCorrect(),
						choice.getSortOrder()))
				.toList();
		return new MissionAdminView(mission.getId(), mission.getPlaceId(), mission.getType(), mission.getTitle(),
				mission.getDescription(), mission.getRewardPoints(), mission.getMaxAttempts(),
				mission.getOxCorrectAnswer(), choiceViews, mission.isDeleted());
	}
}
