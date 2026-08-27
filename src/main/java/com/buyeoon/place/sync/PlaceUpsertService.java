package com.buyeoon.place.sync;

import com.buyeoon.place.entity.PlaceCategory;
import com.buyeoon.place.entity.PlaceEntity;
import com.buyeoon.place.repository.PlaceQueryRepository;
import com.buyeoon.place.sync.OperatingHoursParser.ParsedOperatingHours;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 항목 하나를 upsert한다. 호출자(PlaceSyncService)가 항목 단위로 트랜잭션을 분리해 실패를 흡수할 수 있도록
 * REQUIRES_NEW로 독립 트랜잭션을 연다.
 */
@Component
class PlaceUpsertService {

	private static final String SOURCE_NAME = "TOUR_API";

	private final PlaceQueryRepository placeQueryRepository;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	PlaceUpsertService(PlaceQueryRepository placeQueryRepository) {
		this.placeQueryRepository = placeQueryRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void upsert(PlaceCategory category, TourApiPlaceDetail detail) {
		Point location = geometryFactory.createPoint(new Coordinate(detail.longitude(), detail.latitude()));
		ParsedOperatingHours parsedHours = OperatingHoursParser.parse(detail.useTime());
		Integer admissionFee = OperatingHoursParser.parseFee(detail.useFee());
		String summary = PlaceSummaryGenerator.fromOverview(detail.overview());

		PlaceEntity place = placeQueryRepository.findBySourceNameAndExternalId(SOURCE_NAME, detail.contentId())
				.orElseGet(() -> PlaceEntity.createFromSync(category, detail.title(), summary, detail.overview(),
						detail.address(), location, SOURCE_NAME, detail.contentId(), null, detail.firstImageUrl()));

		place.overwriteFrom(category, detail.title(), summary, detail.overview(), detail.address(), location, null,
				detail.firstImageUrl());
		place.applyOperatingInfo(detail.useTime(), parsedHours.alwaysOpen(), parsedHours.opensAt(),
				parsedHours.closesAt(), admissionFee);
		place.applyDetailInfo(detail.detailInfo());
		placeQueryRepository.saveAndFlush(place);
	}
}
