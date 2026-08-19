package com.buyeoon.place.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
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

	@Column(name = "image_key", columnDefinition = "text")
	private String imageKey;

	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(name = "location", nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "source_name", columnDefinition = "text")
	private String sourceName;

	@Column(name = "external_id", columnDefinition = "text")
	private String externalId;

	@Column(name = "source_url", columnDefinition = "text")
	private String sourceUrl;

	@Column(name = "source_image_href", columnDefinition = "text")
	private String sourceImageHref;

	@Column(name = "operating_hours_raw", columnDefinition = "text")
	private String operatingHoursRaw;

	@Column(name = "opens_at")
	private LocalTime opensAt;

	@Column(name = "closes_at")
	private LocalTime closesAt;

	@Column(name = "admission_fee")
	private Integer admissionFee;

	public static PlaceEntity create(PlaceCategory category, String name, String summary, String description,
			String address, String imageKey, Point location, String sourceName, String externalId, String sourceUrl) {
		location.setSRID(4326);
		PlaceEntity place = new PlaceEntity();
		place.category = category;
		place.name = name;
		place.summary = summary;
		place.description = description;
		place.address = address;
		place.imageKey = imageKey;
		place.location = location;
		place.sourceName = sourceName;
		place.externalId = externalId;
		place.sourceUrl = sourceUrl;
		return place;
	}

	public void applyOperatingInfo(String operatingHoursRaw, LocalTime opensAt, LocalTime closesAt,
			Integer admissionFee) {
		this.operatingHoursRaw = operatingHoursRaw;
		this.opensAt = opensAt;
		this.closesAt = closesAt;
		this.admissionFee = admissionFee;
	}

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JTS Point는 PostGIS geography 매핑을 위해 그대로 보관한다.")
	public void overwriteFrom(PlaceCategory category, String name, String summary, String description, String address,
			Point location, String sourceUrl, String sourceImageHref) {
		location.setSRID(4326);
		this.category = category;
		this.name = name;
		this.summary = summary;
		this.description = description;
		this.address = address;
		this.location = location;
		this.sourceUrl = sourceUrl;
		this.sourceImageHref = sourceImageHref;
	}

	public static PlaceEntity createFromSync(PlaceCategory category, String name, String summary, String description,
			String address, Point location, String sourceName, String externalId, String sourceUrl,
			String sourceImageHref) {
		location.setSRID(4326);
		PlaceEntity place = new PlaceEntity();
		place.category = category;
		place.name = name;
		place.summary = summary;
		place.description = description;
		place.address = address;
		place.location = location;
		place.sourceName = sourceName;
		place.externalId = externalId;
		place.sourceUrl = sourceUrl;
		place.sourceImageHref = sourceImageHref;
		return place;
	}
}
