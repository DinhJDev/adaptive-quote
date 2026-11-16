package dev.dinhj.adaptivequote.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinhj.adaptivequote.catalog.Catalog;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.catalog.VehicleLookup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QuotePricing {
    private static final Offer ASSESSMENT = new Offer("assessment", "Vehicle assessment", 9_900);
    private static final Offer LOG_REVIEW = new Offer("log-review", "Data log review", 4_900);
    private static final Set<String> SPECIALTY_MAKES = Set.of("Audi", "BMW", "Mercedes-Benz", "Porsche");
    private static final Map<String, Integer> MOD_WEIGHTS = Map.of("intake", 1, "exhaust", 1, "intercooler", 2);
    private static final int STANDARD_TUNE_FIRST_YEAR = 1996;
    private final VehicleLookup lookup;

    public QuotePricing(VehicleLookup lookup) {
        this.lookup = lookup;
    }

    public Preview preview(QuoteSelection selection) {
        VehicleDetails vehicle = resolveVehicle(selection.vehicleId(), selection.vehicleDetails());
        var modStatus = selection.modStatus();
        var modIds = selection.modIds();
        var serviceId = selection.serviceId();
        var addOnIds = selection.addOnIds();

        Set<String> modifications = new HashSet<>(modIds);
        if (modifications.size() != modIds.size()
                || modifications.stream().anyMatch(id -> Catalog.MODIFICATIONS.stream()
                .noneMatch(option -> option.id().equals(id)))) {
            throw new InvalidSelection("Choose each applicable modification once.");
        }
        modifications.remove("misc");
        if (("stock".equals(modStatus) && !modifications.isEmpty())
                || ("modified".equals(modStatus) && modifications.isEmpty())) {
            throw new InvalidSelection("Confirm a modification status that matches your selections.");
        }

        if (vehicle.year() < STANDARD_TUNE_FIRST_YEAR) return customReview("older_vehicle");
        if (!MOD_WEIGHTS.keySet().containsAll(modifications)) return customReview("complex_build");
        if (selection.vehicleDetails() != null
                && !lookup.match(vehicle).status().verified()) {
            return customReview("unverified_vehicle");
        }

        int intensity = modifications.stream().mapToInt(MOD_WEIGHTS::get).sum();
        int base = tunePrice(vehicle, intensity);
        var stageOne = new Offer("tune", "Stage 1 calibration tune", base);
        var stageTwo = new Offer("stage-2", "Stage 2 calibration tune", base + 12_500);
        var stageThree = new Offer("stage-3", "Stage 3 calibration tune", base + 25_000);
        boolean needsAssessment = "unsure".equals(modStatus);
        List<Offer> services = needsAssessment ? List.of(ASSESSMENT) : List.of(stageOne, stageTwo, stageThree, ASSESSMENT);
        String recommended = needsAssessment ? ASSESSMENT.id() : switch (intensity) {
            case 0, 1 -> stageOne.id();
            case 2, 3 -> stageTwo.id();
            default -> stageThree.id();
        };
        Offer service = serviceId == null ? null : services.stream()
                .filter(offer -> offer.id().equals(serviceId))
                .findFirst()
                .orElseThrow(() -> new InvalidSelection("Choose an eligible service."));

        List<Offer> addOns = service != null && !ASSESSMENT.equals(service) && modifications.contains("intake")
                ? List.of(LOG_REVIEW) : List.of();
        List<Offer> lineItems = new ArrayList<>();
        if (service != null) {
            lineItems.add(service);
        }
        if (new HashSet<>(addOnIds).size() != addOnIds.size()) {
            throw new InvalidSelection("Choose each add-on once.");
        }
        for (String id : addOnIds) {
            lineItems.add(addOns.stream()
                    .filter(offer -> offer.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new InvalidSelection("Choose an add-on available for this service.")));
        }

        Integer total = service == null ? null : lineItems.stream().mapToInt(Offer::amountMinor).sum();
        return new Preview(Catalog.VERSION, services, addOns, List.copyOf(lineItems), total, "USD", recommended, null);
    }

    static VehicleDetails resolveVehicle(String id, VehicleDetails entered) {
        if (entered != null) {
            return VehicleDetails.validate(entered.make(), entered.model(), entered.year())
                    .filter(vehicle -> vehicle.id().equals(id) && vehicle.equals(entered))
                    .orElseThrow(() -> new InvalidSelection("Confirm the vehicle details again."));
        }
        return Catalog.VEHICLES.stream().filter(option -> option.id().equals(id)).findFirst()
                .map(vehicle -> new VehicleDetails(vehicle.make(), vehicle.model(), vehicle.year()))
                .orElseThrow(() -> new InvalidSelection("Choose or enter your vehicle again."));
    }

    private static int tunePrice(VehicleDetails vehicle, int intensity) {
        int base = 39_900;
        if (SPECIALTY_MAKES.contains(vehicle.make())) base += 7_500;
        if (vehicle.year() < 2010) base += 10_000;
        else if (vehicle.year() < 2015) base += 5_000;
        if (intensity >= 3) base += 10_000;
        return base;
    }

    private static Preview customReview(String reason) {
        return new Preview(Catalog.VERSION, List.of(), List.of(), List.of(), null, "USD", null, reason);
    }

    public static final class InvalidSelection extends RuntimeException {
        public InvalidSelection(String message) {
            super(message);
        }
    }

    public record Offer(String id, String label, int amountMinor) {
    }

    public record Preview(String catalogVersion, List<Offer> eligibleServices,
                          List<Offer> eligibleAddOns, List<Offer> lineItems,
                          Integer totalMinor, String currency,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String recommendedServiceId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String customReviewReason) {
    }
}
