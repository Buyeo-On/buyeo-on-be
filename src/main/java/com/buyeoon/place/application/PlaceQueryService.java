package com.buyeoon.place.application;

import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceProjection;
import com.buyeoon.place.repository.PlaceQueryRepository;
import com.buyeoon.place.repository.SavedPlaceRepository;
import com.buyeoon.trip.TripQueryService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PlaceQueryService {

	private static final double WALKING_METERS_PER_MINUTE = 67.0;

	private final PlaceQueryRepository placeQueryRepository;
	private final SavedPlaceRepository savedPlaceRepository;
	private final TripQueryService tripQueryService;
	private final PublicImageUrlService imageUrls;

	public PlaceQueryService(PlaceQueryRepository placeQueryRepository, SavedPlaceRepository savedPlaceRepository,
			TripQueryService tripQueryService, PublicImageUrlService imageUrls) {
		this.placeQueryRepository = placeQueryRepository;
		this.savedPlaceRepository = savedPlaceRepository;
		this.tripQueryService = tripQueryService;
		this.imageUrls = imageUrls;
	}

	public PlaceListView list(UUID memberId, PlaceCategory category, double latitude, double longitude,
			PlaceCursor cursor, int size) {
		if (!tripQueryService.hasActiveTrip(memberId)) {
			throw new ActiveTripRequiredException();
		}

		List<PlaceProjection> rows = findRows(category, latitude, longitude, cursor, size);
		boolean hasNext = rows.size() > size;
		List<PlaceProjection> page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = hasNext
				? new PlaceCursor(page.getLast().distanceMeters(), page.getLast().place().getId()).encode()
				: null;
		Set<UUID> savedPlaceIds = findSavedPlaceIds(memberId, page);
		List<PlaceItemView> items = page.stream().map(row -> toView(row, savedPlaceIds)).toList();
		return new PlaceListView(items, new PageInfoView(nextCursor, hasNext));
	}

	private Set<UUID> findSavedPlaceIds(UUID memberId, List<PlaceProjection> page) {
		if (page.isEmpty()) {
			return Set.of();
		}
		List<UUID> placeIds = page.stream().map(row -> row.place().getId()).toList();
		return Set.copyOf(savedPlaceRepository.findSavedPlaceIds(memberId, placeIds));
	}

	private List<PlaceProjection> findRows(PlaceCategory category, double latitude, double longitude,
			PlaceCursor cursor, int size) {
		PageRequest pageRequest = PageRequest.of(0, size + 1);
		if (cursor == null) {
			return category == null
					? placeQueryRepository.findFromStart(latitude, longitude, pageRequest)
					: placeQueryRepository.findFromStartByCategory(category, latitude, longitude, pageRequest);
		}
		return category == null
				? placeQueryRepository.findAfter(latitude, longitude, cursor.distanceMeters(), cursor.placeId(),
						pageRequest)
				: placeQueryRepository.findAfterByCategory(category, latitude, longitude, cursor.distanceMeters(),
						cursor.placeId(), pageRequest);
	}

	private PlaceItemView toView(PlaceProjection row, Set<UUID> savedPlaceIds) {
		PlaceEntity place = row.place();
		int distanceMeters = (int) Math.round(row.distanceMeters());
		int walkingMinutes = (int) Math.ceil(row.distanceMeters() / WALKING_METERS_PER_MINUTE);
		String imageUrl = place.getImageKey() == null ? null : imageUrls.create(place.getImageKey());

		return new PlaceItemView(place.getId(), place.getCategory(), place.getName(), place.getSummary(),
				place.getDescription(), place.getAddress(), imageUrl, place.getLocation().getY(),
				place.getLocation().getX(), distanceMeters, walkingMinutes, savedPlaceIds.contains(place.getId()));
	}

	public record PlaceListView(List<PlaceItemView> items, PageInfoView page) {
		public PlaceListView {
			items = List.copyOf(items);
		}
	}

	public record PlaceItemView(UUID placeId, PlaceCategory category, String name, String summary, String description,
			String address, String imageUrl, double latitude, double longitude, Integer distanceMeters,
			Integer walkingMinutes, boolean saved) {
	}

	public record PageInfoView(String nextCursor, boolean hasNext) {
	}
}
