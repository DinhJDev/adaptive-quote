package dev.dinhj.adaptivequote.web;

import dev.dinhj.adaptivequote.catalog.Catalog;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.catalog.VehicleReference;
import dev.dinhj.adaptivequote.extraction.CatalogExtractor;
import dev.dinhj.adaptivequote.extraction.CatalogExtractor.Extraction;
import dev.dinhj.adaptivequote.extraction.LiveExtraction;
import dev.dinhj.adaptivequote.quote.QuotePricing;
import dev.dinhj.adaptivequote.quote.QuotePricing.InvalidSelection;
import dev.dinhj.adaptivequote.quote.QuotePricing.Preview;
import dev.dinhj.adaptivequote.quote.QuoteSelection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuoteApi {
    private final CatalogExtractor extractor;
    private final LiveExtraction live;
    private final QuotePricing pricing;
    private final QuoteSessions sessions;

    public QuoteApi(CatalogExtractor extractor, LiveExtraction live, QuotePricing pricing, QuoteSessions sessions) {
        this.extractor = extractor;
        this.live = live;
        this.pricing = pricing;
        this.sessions = sessions;
    }

    @GetMapping("/catalog")
    CatalogResponse catalog() {
        return new CatalogResponse(Catalog.VERSION, Catalog.VEHICLE_OPTIONS,
                Catalog.MODIFICATIONS, extractor.examples(), live.enabled(), VehicleDetails.MAKES,
                VehicleDetails.FIRST_YEAR, VehicleDetails.LAST_YEAR, VehicleReference.VERSION);
    }

    @PostMapping(path = "/extractions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Extraction> extract(@Valid @RequestBody ExtractionRequest request, HttpServletRequest http) {
        if ("live".equals(request.mode())) sessions.requireOrigin(http);
        if ("modifications".equals(request.scope())) {
            return noStore("live".equals(request.mode())
                    ? live.extractModifications(request.text())
                    : extractor.extractModifications(request.text()));
        }
        Extraction result = switch (request.mode()) {
            case "live" -> live.extract(request.text());
            case "catalog" -> extractor.extractDescription(request.text());
            case "manual" -> extractor.confirmDetails(request.vehicle());
            default -> extractor.extractFixture(request.scenarioId())
                    .orElseThrow(() -> new InvalidSelection("Choose an example from the demo catalog."));
        };
        return noStore(result);
    }

    @PostMapping(path = "/quotes/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Preview> preview(@Valid @RequestBody QuoteSelection selection, HttpServletRequest request) {
        sessions.requireOrigin(request);
        Preview preview = pricing.preview(selection);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (sessions.owner(request).isEmpty()) {
            response.header(HttpHeaders.SET_COOKIE, sessions.cookie(sessions.create()).toString());
        }
        return response.body(preview);
    }

    private <T> ResponseEntity<T> noStore(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    record CatalogResponse(String catalogVersion, List<Catalog.VehicleOption> vehicles,
                           List<Catalog.Modification> modifications,
                           List<CatalogExtractor.Example> examples, boolean liveEnabled,
                           List<String> makes, int minVehicleYear, int maxVehicleYear, String vehicleReferenceVersion) {
    }

    record ExtractionRequest(@NotNull @Pattern(regexp = "fixture|catalog|live|manual") String mode,
                             @Pattern(regexp = QuoteSelection.ID_PATTERN) String scenarioId,
                             @Size(max = 500) String text,
                             @Pattern(regexp = "modifications") String scope,
                             @Valid VehicleDetails vehicle) {
        @AssertTrue
        public boolean isInputValid() {
            if ("manual".equals(mode)) return scenarioId == null && text == null && scope == null && vehicle != null;
            if (vehicle != null) return false;
            if ("catalog".equals(mode) || "live".equals(mode)) {
                return scenarioId == null && text != null && !text.isBlank();
            }
            return "fixture".equals(mode) && text == null && scope == null && scenarioId != null;
        }
    }
}
