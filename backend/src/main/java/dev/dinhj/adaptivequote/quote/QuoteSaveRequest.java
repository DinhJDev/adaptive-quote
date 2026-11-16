package dev.dinhj.adaptivequote.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record QuoteSaveRequest(
        @NotNull @Pattern(regexp = QuoteSelection.ID_PATTERN) String catalogVersion,
        @NotNull @AssertTrue Boolean confirmed,
        @NotNull @Valid QuoteSelection selection
) {
}
