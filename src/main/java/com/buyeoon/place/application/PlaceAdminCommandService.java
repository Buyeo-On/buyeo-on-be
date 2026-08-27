package com.buyeoon.place.application;

import com.buyeoon.place.api.InvalidPlaceRequestException;
import com.buyeoon.place.api.PlaceAdminCreateRequest;
import com.buyeoon.place.api.PlaceAdminUpdateRequest;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceAdminCommandService {

	private final PlaceQueryRepository placeQueryRepository;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceAdminCommandService(PlaceQueryRepository placeQueryRepository) {
		this.placeQueryRepository = placeQueryRepository;
	}

	@Transactional
	public UUID create(PlaceAdminCreateRequest request) {
		PlaceCategory category = category(request.category());
		String name = requiredText(request.name());
		Point location = point(request.latitude(), request.longitude());

		PlaceEntity place = PlaceEntity.create(category, name, request.summary(), request.description(),
				request.address(), request.imageKey(), location, null, null, null);
		place.applyOperatingInfo(request.operatingHoursRaw(), alwaysOpen(request.alwaysOpen()),
				time(request.opensAt()), time(request.closesAt()), request.admissionFee());
		return placeQueryRepository.save(place).getId();
	}

	@Transactional
	public void update(UUID placeId, PlaceAdminUpdateRequest request) {
		PlaceEntity place = placeQueryRepository.findById(placeId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(PlaceNotFoundException::new);

		PlaceCategory category = category(request.category());
		String name = requiredText(request.name());
		Point location = point(request.latitude(), request.longitude());

		place.applyAdminUpdate(category, name, request.summary(), request.description(), request.address(),
				request.imageKey(), location);
		place.applyOperatingInfo(request.operatingHoursRaw(), alwaysOpen(request.alwaysOpen()),
				time(request.opensAt()), time(request.closesAt()), request.admissionFee());
	}

	@Transactional
	public void delete(UUID placeId) {
		PlaceEntity place = placeQueryRepository.findById(placeId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(PlaceNotFoundException::new);
		place.softDelete();
	}

	private PlaceCategory category(String value) {
		if (value == null) {
			throw new InvalidPlaceRequestException();
		}
		try {
			return PlaceCategory.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new InvalidPlaceRequestException();
		}
	}

	private String requiredText(String value) {
		if (value == null || value.isBlank()) {
			throw new InvalidPlaceRequestException();
		}
		return value;
	}

	private Point point(Double latitude, Double longitude) {
		if (latitude == null || longitude == null) {
			throw new InvalidPlaceRequestException();
		}
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new InvalidPlaceRequestException();
		}
		return geometryFactory.createPoint(new Coordinate(longitude, latitude));
	}

	private boolean alwaysOpen(Boolean value) {
		return value != null && value;
	}

	private LocalTime time(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalTime.parse(value);
		} catch (DateTimeParseException exception) {
			throw new InvalidPlaceRequestException();
		}
	}
}
