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
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MissionAdminCommandService {

	private final MissionQueryRepository missionQueryRepository;
	private final MissionChoiceRepository missionChoiceRepository;
	private final MissionSubmissionRepository missionSubmissionRepository;
	private final PlaceQueryRepository placeQueryRepository;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionAdminCommandService(MissionQueryRepository missionQueryRepository,
			MissionChoiceRepository missionChoiceRepository, MissionSubmissionRepository missionSubmissionRepository,
			PlaceQueryRepository placeQueryRepository) {
		this.missionQueryRepository = missionQueryRepository;
		this.missionChoiceRepository = missionChoiceRepository;
		this.missionSubmissionRepository = missionSubmissionRepository;
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
		Point location = point(request.latitude(), request.longitude(), place.getLocation());

		MissionEntity mission;
		try {
			mission = switch (type) {
				case MULTIPLE_CHOICE -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					yield MissionEntity.multipleChoice(place.getId(), location, title, description,
							request.rewardPoints(), request.maxAttempts());
				}
				case OX -> {
					if (request.oxCorrectAnswer() == null) {
						throw new InvalidMissionRequestException();
					}
					requireNoChoices(request.choices());
					yield MissionEntity.ox(place.getId(), location, title, description, request.rewardPoints(),
							request.maxAttempts(), request.oxCorrectAnswer());
				}
				case PHOTO -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					requireNoChoices(request.choices());
					if (request.maxAttempts() != null) {
						throw new InvalidMissionRequestException();
					}
					yield MissionEntity.photo(place.getId(), location, title, description, request.rewardPoints());
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
		mission.updateLocation(point(request.latitude(), request.longitude(), mission.getLocation()));

		try {
			switch (mission.getType()) {
				case MULTIPLE_CHOICE -> {
					requireNoOxAnswer(request.oxCorrectAnswer());
					requireNoSubmittedChoices(missionId);
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

	@Transactional
	public void restore(UUID missionId) {
		MissionEntity mission = missionQueryRepository.findById(missionId).orElseThrow(MissionNotFoundException::new);
		PlaceEntity place = placeQueryRepository.findById(mission.getPlaceId()).orElseThrow(MissionNotFoundException::new);
		if (place.isDeleted()) {
			throw new InvalidMissionRequestException();
		}
		mission.restore();
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

	private void requireNoSubmittedChoices(UUID missionId) {
		List<UUID> choiceIds = missionChoiceRepository.findByMissionIdOrderBySortOrderAsc(missionId).stream()
				.map(MissionChoiceEntity::getId).collect(Collectors.toList());
		if (!choiceIds.isEmpty() && missionSubmissionRepository.existsByChoiceIdIn(choiceIds)) {
			throw new MissionChoiceInUseException();
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

	/** 좌표가 둘 다 없으면 {@code fallback}(장소 좌표 등)을 쓰고, 있으면 범위를 검증해 새 좌표를 만든다. */
	private Point point(Double latitude, Double longitude, Point fallback) {
		if (latitude == null && longitude == null) {
			return fallback;
		}
		if (latitude == null || longitude == null) {
			throw new InvalidMissionRequestException();
		}
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new InvalidMissionRequestException();
		}
		return geometryFactory.createPoint(new Coordinate(longitude, latitude));
	}
}
