package com.buyeoon.place.application;

import com.buyeoon.common.storage.PublicImageObjectStore;
import com.buyeoon.common.storage.PublicImageObjectStore.PublicImageObject;
import com.buyeoon.common.storage.PublicImageUploadPresigner;
import com.buyeoon.common.storage.PublicImageUploadPresigner.PublicImageUploadTarget;
import com.buyeoon.common.storage.PublicImageUploadUrlView;
import com.buyeoon.place.api.InvalidPlaceRequestException;
import com.buyeoon.place.api.PlaceAdminCreateRequest;
import com.buyeoon.place.api.PlaceAdminUpdateRequest;
import com.buyeoon.mission.entity.MissionEntity;
import com.buyeoon.mission.repository.MissionQueryRepository;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceAdminCommandService {

	private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png",
			"image/webp", "webp");
	private static final String IMAGE_KEY_PREFIX = "public/places/";

	private final PlaceQueryRepository placeQueryRepository;
	private final MissionQueryRepository missionQueryRepository;
	private final PublicImageUploadPresigner publicImageUploadPresigner;
	private final PublicImageObjectStore publicImageObjectStore;
	private final long maxUploadBytes;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceAdminCommandService(PlaceQueryRepository placeQueryRepository,
			MissionQueryRepository missionQueryRepository, PublicImageUploadPresigner publicImageUploadPresigner,
			PublicImageObjectStore publicImageObjectStore,
			@Value("${storage.images.max-upload-bytes:10485760}") long maxUploadBytes) {
		this.placeQueryRepository = placeQueryRepository;
		this.missionQueryRepository = missionQueryRepository;
		this.publicImageUploadPresigner = publicImageUploadPresigner;
		this.publicImageObjectStore = publicImageObjectStore;
		this.maxUploadBytes = maxUploadBytes;
	}

	@Transactional
	public UUID create(PlaceAdminCreateRequest request) {
		PlaceCategory category = category(request.category());
		String name = requiredText(request.name());
		Point location = point(request.latitude(), request.longitude());
		String imageKey = verifiedImageKey(request.imageKey());

		PlaceEntity place = PlaceEntity.create(category, name, request.summary(), request.description(),
				request.address(), imageKey, location, null, null, null);
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
		String imageKey = verifiedImageKey(request.imageKey());

		place.applyAdminUpdate(category, name, request.summary(), request.description(), request.address(), imageKey,
				location);
		place.applyOperatingInfo(request.operatingHoursRaw(), alwaysOpen(request.alwaysOpen()),
				time(request.opensAt()), time(request.closesAt()), request.admissionFee());
	}

	public PublicImageUploadUrlView createImageUploadUrl(String contentType, long fileSizeBytes) {
		if (contentType == null || !ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
			throw new InvalidPlaceRequestException();
		}
		if (fileSizeBytes <= 0 || fileSizeBytes > maxUploadBytes) {
			throw new InvalidPlaceRequestException();
		}
		String imageKey = IMAGE_KEY_PREFIX + UUID.randomUUID() + "." + CONTENT_TYPE_EXTENSIONS.get(contentType);
		PublicImageUploadTarget target = publicImageUploadPresigner.presign(imageKey, contentType, fileSizeBytes);
		return new PublicImageUploadUrlView(imageKey, target.uploadUrl(), "PUT", target.headers(), 200,
				target.expiresAt());
	}

	private String verifiedImageKey(String imageKey) {
		if (imageKey == null) {
			return null;
		}
		if (!imageKey.startsWith("public/")) {
			throw new InvalidPlaceRequestException();
		}
		PublicImageObject object = publicImageObjectStore.head(imageKey).orElseThrow(InvalidPlaceRequestException::new);
		if (object.fileSizeBytes() != object.declaredFileSizeBytes()
				|| !object.contentType().equals(object.declaredContentType())
				|| !ALLOWED_IMAGE_CONTENT_TYPES.contains(object.contentType())) {
			throw new InvalidPlaceRequestException();
		}
		return imageKey;
	}

	@Transactional
	public void delete(UUID placeId) {
		PlaceEntity place = placeQueryRepository.findById(placeId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(PlaceNotFoundException::new);
		place.softDelete();
		List<MissionEntity> missions = missionQueryRepository.findByPlaceIdAndDeletedAtIsNull(placeId);
		missions.forEach(MissionEntity::softDelete);
	}

	@Transactional
	public void restore(UUID placeId) {
		PlaceEntity place = placeQueryRepository.findById(placeId).orElseThrow(PlaceNotFoundException::new);
		place.restore();
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
