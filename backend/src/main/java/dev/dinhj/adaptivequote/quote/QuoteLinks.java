package dev.dinhj.adaptivequote.quote;

import dev.dinhj.adaptivequote.catalog.Catalog.VehicleOption;
import dev.dinhj.adaptivequote.extraction.LiveExtraction;
import dev.dinhj.adaptivequote.quote.QuotePricing.InvalidSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuoteLinks {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final QuotePricing pricing;

    QuoteLinks(JdbcClient jdbc, ObjectMapper json, QuotePricing pricing) {
        this.jdbc = jdbc;
        this.json = json;
        this.pricing = pricing;
    }

    public Created create(CreateRequest request) {
        var selection = request.selection();
        for (var part : request.parts()) {
            if (!part.id().equals("unclassified") && !selection.modIds().contains(part.id())) {
                throw new InvalidSelection("Confirm the part categories before creating a link.");
            }
            if (part.label().indexOf(0) >= 0 || LiveExtraction.containsContactDetails(part.label())) {
                throw new InvalidSelection("Remove contact details and links from part names before creating a quote link.");
            }
        }
        var details = QuotePricing.resolveVehicle(selection.vehicleId(), selection.vehicleDetails());
        var vehicle = new VehicleOption(selection.vehicleId(),
                details.year() + " " + details.make() + " " + details.model(),
                details.make(), details.model(), details.year(),
                selection.vehicleDetails() == null ? null : true, null);
        var preview = pricing.preview(new QuoteSelection(selection.vehicleId(), selection.modStatus(),
                selection.modIds(), null, List.of(), selection.vehicleDetails()));
        String recommendation = preview.eligibleServices().stream()
                .filter(offer -> offer.id().equals(preview.recommendedServiceId()))
                .map(QuotePricing.Offer::label).findFirst().orElse(null);
        var snapshot = new Snapshot(vehicle, selection.modStatus(), selection.modIds(), request.parts(), recommendation);
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO quote_links (id, record_version, snapshot)
                VALUES (:id, 1, CAST(:snapshot AS jsonb))
                RETURNING expires_at
                """)
                .param("id", id)
                .param("snapshot", json.writeValueAsString(snapshot))
                .query((result, row) -> new Created(id, result.getObject("expires_at", OffsetDateTime.class)))
                .single();
    }

    public Optional<Snapshot> open(UUID id) {
        return jdbc.sql("""
                SELECT snapshot FROM quote_links
                WHERE id = :id AND record_version = 1 AND expires_at > now()
                """)
                .param("id", id)
                .query((result, row) -> json.readValue(result.getString("snapshot"), Snapshot.class))
                .optional();
    }

    public record CreateRequest(@NotNull @Valid QuoteSelection selection,
                                @NotNull @Size(max = 72) List<@NotNull @Valid Part> parts) {}

    public record Part(@NotNull @Pattern(regexp = QuoteSelection.ID_PATTERN) String id,
                       @NotBlank @Size(max = 500) String label) {}

    public record Snapshot(VehicleOption vehicle, String modStatus, List<String> modIds, List<Part> parts,
                           String recommendation) {}

    public record Created(UUID id, OffsetDateTime expiresAt) {}
}
