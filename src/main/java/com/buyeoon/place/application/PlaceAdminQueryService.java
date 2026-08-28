package com.buyeoon.place.application;

import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.place.api.PlaceAdminListView;
import com.buyeoon.place.api.PlaceAdminView;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PlaceAdminQueryService {

	private final PlaceQueryRepository placeQueryRepository;
	private final PublicImageUrlService imageUrls;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public PlaceAdminQueryService(PlaceQueryRepository placeQueryRepository, PublicImageUrlService imageUrls) {
		this.placeQueryRepository = placeQueryRepository;
		this.imageUrls = imageUrls;
	}

	public PlaceAdminListView list(PlaceCategory category, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<PlaceEntity> result = category == null
				? placeQueryRepository.findByDeletedAtIsNull(pageRequest)
				: placeQueryRepository.findByDeletedAtIsNullAndCategory(category, pageRequest);
		return new PlaceAdminListView(result.getContent().stream().map(this::toView).toList(), page, size,
				result.getTotalElements(), result.getTotalPages());
	}

	public PlaceAdminView get(UUID placeId) {
		PlaceEntity place = placeQueryRepository.findById(placeId).filter(candidate -> !candidate.isDeleted())
				.orElseThrow(PlaceNotFoundException::new);
		return toView(place);
	}

	private PlaceAdminView toView(PlaceEntity place) {
		String imageUrl = place.getImageKey() != null ? imageUrls.create(place.getImageKey())
				: place.getSourceImageHref();
		return new PlaceAdminView(place.getId(), place.getCategory(), place.getName(), place.getSummary(),
				place.getDescription(), place.getAddress(), imageUrl, place.getLocation().getY(),
				place.getLocation().getX(), place.getOperatingHoursRaw(), place.isAlwaysOpen(), place.getOpensAt(),
				place.getClosesAt(), place.getAdmissionFee(), place.isDeleted());
	}
}
