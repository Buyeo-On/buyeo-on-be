package com.buyeoon.member.application;

import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.member.application.CitizenCardCreationService.LocationCommand;
import org.springframework.stereotype.Service;

@Service
public final class CitizenCardLocationVerifier {

	private final BuyeoBoundary boundary;

	public CitizenCardLocationVerifier(BuyeoBoundary boundary) {
		this.boundary = boundary;
	}

	public void verify(LocationCommand location) {
		if (!boundary.covers(location.latitude(), location.longitude())) {
			throw new OutsideBuyeoException();
		}
	}
}
