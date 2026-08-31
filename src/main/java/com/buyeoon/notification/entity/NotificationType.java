package com.buyeoon.notification.entity;

public enum NotificationType {
	POINT, BADGE, NEARBY_QUIZ, DISCOUNT, CITIZEN_CARD, BUYEO_NEWS, BUYEO_ENTRY;

	public String iconKey() {
		return "public/notifications/" + name().toLowerCase() + ".svg";
	}
}
