package com.buyeoon.trip;

import com.buyeoon.badge.entity.BadgeEntity;
import java.time.Instant;

public record EarnedBadgeProjection(BadgeEntity badge, Instant earnedAt) {
}
