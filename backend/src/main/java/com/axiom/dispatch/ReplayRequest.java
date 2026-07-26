package com.axiom.dispatch;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** Replay one or many dead letters (FR-INT-005). */
public record ReplayRequest(@NotEmpty(message = "Select at least one dead letter to replay")
                            List<UUID> deadLetterIds) {
}
