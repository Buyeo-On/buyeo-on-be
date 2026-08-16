package com.buyeoon.trip;

import com.buyeoon.trip.TripStartService.TripStartCommand;
import com.buyeoon.trip.TripStartService.TripView;
import java.util.UUID;

public interface TripStarter {

	TripView start(UUID memberId, String idempotencyKey, TripStartCommand command);
}
