package com.buyeoon.notification.entity;

public enum NotificationType {
	POINT,
	BADGE,
	NEARBY_QUIZ,
	DISCOUNT,
	CITIZEN_CARD,
	BUYEO_NEWS;

	public String iconKey() {
		return "public/notifications/" + name().toLowerCase() + ".svg";
	}
}
