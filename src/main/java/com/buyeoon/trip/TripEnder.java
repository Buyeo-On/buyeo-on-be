package com.buyeoon.trip;

import com.buyeoon.trip.TripStartService.TripView;
import java.util.UUID;

public interface TripEnder {

	TripView end(UUID memberId, UUID tripId, String idempotencyKey);
}
