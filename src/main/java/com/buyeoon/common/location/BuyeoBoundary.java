package com.buyeoon.common.location;

import java.io.IOException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class BuyeoBoundary {

	private final GeometryFactory geometryFactory = new GeometryFactory();
	private final Resource resource;
	private final ObjectReader objectReader;
	private volatile PreparedGeometry boundary;

	public BuyeoBoundary(
			@Value("${location.buyeo-boundary:classpath:boundaries/buyeo-44760.geojson}") Resource resource,
			ObjectMapper objectMapper) {
		this.resource = resource;
		this.objectReader = objectMapper.reader();
	}

	public boolean covers(double latitude, double longitude) {
		return getBoundary().covers(geometryFactory.createPoint(new Coordinate(longitude, latitude)));
	}

	private PreparedGeometry getBoundary() {
		PreparedGeometry loaded = boundary;
		if (loaded == null) {
			synchronized (this) {
				loaded = boundary;
				if (loaded == null) {
					loaded = loadBoundary();
					boundary = loaded;
				}
			}
		}
		return loaded;
	}

	private PreparedGeometry loadBoundary() {
		try (var input = resource.getInputStream()) {
			JsonNode root = objectReader.readTree(input);
			return PreparedGeometryFactory.prepare(readGeometry(findGeometry(root)));
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException("부여 행정구역 경계를 읽을 수 없습니다.", exception);
		}
	}

	private JsonNode findGeometry(JsonNode root) {
		String type = requiredText(root, "type");
		return switch (type) {
			case "FeatureCollection" -> {
				JsonNode features = root.get("features");
				if (features == null || !features.isArray() || features.size() != 1) {
					throw new IllegalArgumentException("부여 경계 Feature는 하나여야 합니다.");
				}
				yield required(features.get(0), "geometry");
			}
			case "Feature" -> required(root, "geometry");
			case "Polygon", "MultiPolygon" -> root;
			default -> throw new IllegalArgumentException("지원하지 않는 GeoJSON 형식입니다.");
		};
	}

	private Geometry readGeometry(JsonNode geometry) {
		String type = requiredText(geometry, "type");
		JsonNode coordinates = required(geometry, "coordinates");
		return switch (type) {
			case "Polygon" -> readPolygon(coordinates);
			case "MultiPolygon" -> {
				Polygon[] polygons = new Polygon[coordinates.size()];
				for (int index = 0; index < coordinates.size(); index++) {
					polygons[index] = readPolygon(coordinates.get(index));
				}
				yield geometryFactory.createMultiPolygon(polygons);
			}
			default -> throw new IllegalArgumentException("경계는 Polygon 또는 MultiPolygon이어야 합니다.");
		};
	}

	private Polygon readPolygon(JsonNode coordinates) {
		if (!coordinates.isArray() || coordinates.isEmpty()) {
			throw new IllegalArgumentException("Polygon 좌표가 없습니다.");
		}
		LinearRing shell = readRing(coordinates.get(0));
		LinearRing[] holes = new LinearRing[coordinates.size() - 1];
		for (int index = 1; index < coordinates.size(); index++) {
			holes[index - 1] = readRing(coordinates.get(index));
		}
		return geometryFactory.createPolygon(shell, holes);
	}

	private LinearRing readRing(JsonNode points) {
		if (!points.isArray() || points.size() < 4) {
			throw new IllegalArgumentException("경계 고리는 네 점 이상이어야 합니다.");
		}
		Coordinate[] coordinates = new Coordinate[points.size()];
		for (int index = 0; index < points.size(); index++) {
			JsonNode point = points.get(index);
			if (point == null || !point.isArray() || point.size() < 2 || !point.get(0).isNumber()
					|| !point.get(1).isNumber()) {
				throw new IllegalArgumentException("경계 좌표가 올바르지 않습니다.");
			}
			coordinates[index] = new Coordinate(point.get(0).doubleValue(), point.get(1).doubleValue());
		}
		return geometryFactory.createLinearRing(coordinates);
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalArgumentException("GeoJSON 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalArgumentException("GeoJSON 문자열 필드가 올바르지 않습니다: " + field);
		}
		return value.stringValue();
	}
}
