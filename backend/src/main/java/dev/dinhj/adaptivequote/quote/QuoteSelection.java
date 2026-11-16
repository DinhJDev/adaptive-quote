package dev.dinhj.adaptivequote.quote;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record QuoteSelection(
        @NotNull @Pattern(regexp = ID_PATTERN) String vehicleId,
        @NotNull @Pattern(regexp = "stock|modified|unsure") String modStatus,
        @NotNull @Size(max = 6) List<@NotNull @Pattern(regexp = ID_PATTERN) String> modIds,
        @JsonProperty(required = true) @Pattern(regexp = ID_PATTERN) String serviceId,
        @NotNull @Size(max = 1) List<@NotNull @Pattern(regexp = ID_PATTERN) String> addOnIds,
        @Valid @JsonInclude(JsonInclude.Include.NON_NULL) VehicleDetails vehicleDetails
) {
    public static final String ID_PATTERN = "[a-z0-9][a-z0-9-]{0,39}";

    QuoteSelection canonical() {
        return new QuoteSelection(vehicleId, modStatus, modIds.stream().sorted().toList(), serviceId,
                addOnIds.stream().sorted().toList(), vehicleDetails);
    }
}
