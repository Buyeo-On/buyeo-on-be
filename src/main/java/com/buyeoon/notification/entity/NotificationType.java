package com.buyeoon.notification.entity;

/** 알림 종류다. 화면 아이콘 키는 유형 이름을 따른다. */
public enum NotificationType {
	POINT, BADGE, NEARBY_QUIZ, DISCOUNT, CITIZEN_CARD, BUYEO_NEWS, BUYEO_ENTRY, BUYEO_EXIT;

	/** 공개 스토리지의 유형별 아이콘 객체 키를 반환한다. */
	public String iconKey() {
		return "public/notifications/" + name().toLowerCase() + ".svg";
	}
}
