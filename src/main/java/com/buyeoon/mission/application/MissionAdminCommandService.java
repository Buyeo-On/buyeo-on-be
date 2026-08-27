package com.buyeoon.mission.application;

import com.buyeoon.mission.api.InvalidMissionRequestException;
import com.buyeoon.mission.api.MissionAdminCreateRequest;
import com.buyeoon.mission.api.MissionAdminUpdateRequest;
import com.buyeoon.mission.api.MissionChoiceRequest;
import com.buyeoon.mission.entity.MissionChoiceEntity;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionChoiceRepository;
import com.buyeoon.mission.repository.MissionQueryRepository;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionAdminCommandService {

	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;
	private final PlaceQueryRepository placeQueryRepository;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionAdminCommandService(MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, PlaceQueryRepository placeQueryRepository) {
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
		this.placeQueryRepository = placeQueryRepository;
	}

	@Transactional
	public UUID create(MissionAdminCreateRequest request) {
		if (request.placeId() == null) {
			throw new InvalidMissionRequestException();
		}
		PlaceEntity place = placeQueryRepository.findById(request.placeId())
				.filter(candidate -> !candidate.isDeleted()).orElseThrow(InvalidMissionRequestException::new);
		MissionType type = missionType(request.type());
		String title = requiredText(request.title());
		String description = requiredText(request.description());

		MissionEntity mission;
		try {
			mission = switch (type) {
				case MULTIPLE_CHOICE -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					yield MissionEntity.multipleChoice(place.getId(), place.getLocation(), title, description,
							request.rewardPoints(), request.maxAttempts());
				}
				case OX -> {
					if (request.oxCorrectAnswer() == null) {
						throw new InvalidMissionRequestException();
					}
					requireNoChoices(request.choices());
					yield MissionEntity.ox(place.getId(), place.getLocation(), title, description,
							request.rewardPoints(), request.maxAttempts(), request.oxCorrectAnswer());
				}
				case PHOTO -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					requireNoChoices(request.choices());
					if (request.maxAttempts() != null) {
						throw new InvalidMissionRequestException();
					}
					yield MissionEntity.photo(place.getId(), place.getLocation(), title, description,
							request.rewardPoints());
				}
			};
		} catch (IllegalArgumentException exception) {
			throw new InvalidMissionRequestException();
		}
		missionQueryRepository.save(mission);

		if (type == MissionType.MULTIPLE_CHOICE) {
			saveChoices(mission.getId(), request.choices());
		}
		return mission.getId();
	}

	@Transactional
	public void update(UUID missionId, MissionAdminUpdateRequest request) {
		MissionEntity mission = missionQueryRepository.findById(missionId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(MissionNotFoundException::new);
		String title = requiredText(request.title());
		String description = requiredText(request.description());

		try {
			switch (mission.getType()) {
				case MULTIPLE_CHOICE -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					mission.updateMultipleChoice(title, description, request.rewardPoints(), request.maxAttempts());
					missionChoiceRepository.deleteByMissionId(missionId);
					missionChoiceRepository.flush();
					saveChoices(missionId, request.choices());
				}
				case OX -> {
					if (request.oxCorrectAnswer() == null) {
						throw new InvalidMissionRequestException();
					}
					requireNoChoices(request.choices());
					mission.updateOx(title, description, request.rewardPoints(), request.maxAttempts(),
							request.oxCorrectAnswer());
				}
				case PHOTO -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					requireNoChoices(request.choices());
					if (request.maxAttempts() != null) {
						throw new InvalidMissionRequestException();
					}
					mission.updatePhoto(title, description, request.rewardPoints());
				}
			}
		} catch (IllegalArgumentException | IllegalStateException exception) {
			throw new InvalidMissionRequestException();
		}
	}

	@Transactional
	public void delete(UUID missionId) {
		MissionEntity mission = missionQueryRepository.findById(missionId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(MissionNotFoundException::new);
		mission.softDelete();
	}

	private void saveChoices(UUID missionId, List<MissionChoiceRequest> choices) {
		if (choices == null || choices.isEmpty()) {
			throw new InvalidMissionRequestException();
		}
		boolean hasCorrect = choices.stream().anyMatch(MissionChoiceRequest::correct);
		if (!hasCorrect) {
			throw new InvalidMissionRequestException();
		}
		for (MissionChoiceRequest choice : choices) {
			String label = requiredText(choice.label());
			missionChoiceRepository.save(MissionChoiceEntity.create(missionId, label, choice.correct(),
					choice.sortOrder()));
		}
	}

	private void requireNoChoices(List<MissionChoiceRequest> choices) {
		if (choices != null && !choices.isEmpty()) {
			throw new InvalidMissionRequestException();
		}
	}

	private void requireNoOxAnswer(Boolean oxCorrectAnswer) {
		if (oxCorrectAnswer != null) {
			throw new InvalidMissionRequestException();
		}
	}

	private MissionType missionType(String value) {
		if (value == null) {
			throw new InvalidMissionRequestException();
		}
		try {
			return MissionType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new InvalidMissionRequestException();
		}
	}

	private String requiredText(String value) {
		if (value == null || value.isBlank()) {
			throw new InvalidMissionRequestException();
		}
		return value;
	}
}
