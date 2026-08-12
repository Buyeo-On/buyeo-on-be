package com.buyeoon.member.application;

import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardCommand;
import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardView;
import java.util.UUID;

public interface CitizenCardCreator {

	CitizenCardView create(UUID memberId, String idempotencyKey, CitizenCardCommand command);
}
