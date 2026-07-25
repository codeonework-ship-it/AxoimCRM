package com.axiom.reference;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EntryMutation(
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") String code,
        @NotBlank @Size(max = 160) String label,
        @Min(0) int sortOrder,
        boolean active,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
