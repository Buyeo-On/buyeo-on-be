package com.buyeoon.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "places")
public class PlaceEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "category", nullable = false, columnDefinition = "place_category")
	private PlaceCategory category;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "address", columnDefinition = "text")
	private String address;

	@Column(name = "image_url", columnDefinition = "text")
	private String imageUrl;

	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(name = "location", nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "source_name", columnDefinition = "text")
	private String sourceName;

	@Column(name = "source_url", columnDefinition = "text")
	private String sourceUrl;

	public static PlaceEntity create(
			PlaceCategory category,
			String name,
			String summary,
			String description,
			String address,
			String imageUrl,
			Point location,
			String sourceName,
			String sourceUrl) {
		location.setSRID(4326);
		PlaceEntity place = new PlaceEntity();
		place.category = category;
		place.name = name;
		place.summary = summary;
		place.description = description;
		place.address = address;
		place.imageUrl = imageUrl;
		place.location = location;
		place.sourceName = sourceName;
		place.sourceUrl = sourceUrl;
		return place;
	}
}
