package dev.dinhj.adaptivequote.extraction;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinhj.adaptivequote.catalog.Catalog;
import dev.dinhj.adaptivequote.catalog.Catalog.Vehicle;
import dev.dinhj.adaptivequote.catalog.Catalog.VehicleOption;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.catalog.VehicleLookup;
import dev.dinhj.adaptivequote.catalog.VehicleReference;
import dev.dinhj.adaptivequote.quote.QuotePricing.InvalidSelection;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CatalogExtractor {
    static final String UNCLASSIFIED = "unclassified";
    private static final List<Example> EXAMPLES = List.of(
            example("vw-gti", "2020 VW GTI"),
            example("nissan-gtr", "2000 Nissan GTR"),
            example("ford-mustang", "2022 Ford Mustang GT")
    );
    private static final Map<String, String> FIXTURE_DESCRIPTIONS = Map.of(
            "clear", "2022 Toyota GR86 with GR intake",
            "ambiguous", "Toyota 86 with an exhaust",
            "missing-mods", "2022 Ford Mustang GT"
    );
    private static final Pattern YEAR = Pattern.compile("\\b(?:18|19|20)\\d{2}\\b");
    private static final Pattern TOYOTA_86 = Pattern.compile(
            "\\btoyota 86\\b|^ 86 (?:$|with\\b|(?:19|20)\\d{2}\\b)|\\b(?:19|20)\\d{2} 86\\b|\\bmy 86\\b");
    private static final Pattern EXCLUSION = Pattern.compile("\\b(?:no|without|stock|factory|original)\\s+$");
    private static final List<ModTerms> MOD_TERMS = List.of(
            new ModTerms("intake", Pattern.compile("\\bintakes?\\b")),
            new ModTerms("exhaust", Pattern.compile("\\b(?:exhausts?|cat back|catbacks?|muffler delete|headers|downpipes?)\\b")),
            new ModTerms("intercooler", Pattern.compile("\\bintercoolers?\\b")),
            new ModTerms("turbo", Pattern.compile("\\b(?:turbo upgrades?|upgraded turbo|aftermarket turbo|bigger turbo)\\b")),
            new ModTerms("other-performance", Pattern.compile("\\b(?:engine swaps?|swapped engine|standalone ecu|superchargers?)\\b")),
            new ModTerms("misc", Pattern.compile("\\b(?:body kits?|cosmetic|chassis|coilovers?|coil overs?|suspension)\\b"))
    );
    private final VehicleLookup lookup;

    public CatalogExtractor(VehicleLookup lookup) {
        this.lookup = lookup;
    }

    public List<Example> examples() {
        return EXAMPLES;
    }

    public Optional<Extraction> extractFixture(String scenarioId) {
        return EXAMPLES.stream().filter(example -> example.id().equals(scenarioId))
                .map(Example::description).findFirst()
                .or(() -> Optional.ofNullable(FIXTURE_DESCRIPTIONS.get(scenarioId)))
                .map(description -> extract(description, "fixture"));
    }

    public Extraction extractDescription(String description) {
        return extract(description, "catalog");
    }

    public Extraction extractModifications(String description) {
        String text = normalize(description);
        List<String> ids = modIds(text);
        boolean stock = isStock(text, ids);
        var parts = stock ? List.<Part>of() : describeParts(description, true);
        return modificationExtraction("catalog", new Facts(ids, stock, parts));
    }

    Extraction resolveLiveModifications(String description, OpenAiExtractor.Result result) {
        Facts facts = liveFacts(result);
        return facts.parts().isEmpty() && !facts.stockMention()
                ? extractModifications(description).withFallback("unavailable")
                : modificationExtraction("live", facts);
    }

    private static Extraction modificationExtraction(String source, Facts facts) {
        return new Extraction(source, "vehicle_extraction_v1", facts, new Match("unsupported", List.of()), null);
    }

    public Extraction confirmDetails(VehicleDetails details) {
        var vehicle = VehicleDetails.validate(details.make(), details.model(), details.year())
                .orElseThrow(() -> new InvalidSelection("Choose a supported make and enter a valid model and year."));
        VehicleOption option = optionFor(vehicle, true);
        return new Extraction("manual", "vehicle_extraction_v1", new Facts(List.of(), false),
                new Match("matched", List.of(option)), null);
    }

    private Extraction extract(String description, String source) {
        String text = normalize(description);
        boolean useVpic = !source.equals("fixture");
        List<Vehicle> named = namedVehicles(text);
        boolean toyota86 = TOYOTA_86.matcher(text).find();
        List<Integer> statedYears = years(text);
        Match resolved = match(named, statedYears, toyota86);
        if (named.isEmpty() && !toyota86 && statedYears.size() == 1) {
            String identity = vehicleWording(text).strip();
            if (years(identity).size() == 1) {
                String alias = YEAR.matcher(identity).replaceFirst("").strip();
                var candidate = clueFromAlias(alias, identity)
                        .flatMap(clue -> VehicleDetails.validate(clue.make(), clue.model(), statedYears.getFirst()))
                        .map(details -> optionFor(details, useVpic && !hasConflictingMake(identity, details.make())));
                if (candidate.isPresent() && !requiresReferenceConfirmation(candidate.get())) {
                    resolved = new Match("matched", List.of(candidate.get()));
                }
            }
        }
        var modIds = modIds(text);
        var wording = description.split("(?i)\\b(?:with|has|running)\\b", 2);
        var parts = wording.length == 2 ? describeParts(wording[1], false) : List.<Part>of();
        return new Extraction(source, "vehicle_extraction_v1", new Facts(modIds, isStock(text, modIds), parts),
                finishMatch(resolved, text, useVpic), null);
    }

    Extraction resolveLive(String description, OpenAiExtractor.Result result) {
        String text = normalize(description);
        String identityText = result.vehicles().isEmpty() ? text : vehicleWording(text);
        List<Vehicle> named = namedVehicles(identityText);
        boolean toyota86 = TOYOTA_86.matcher(identityText).find();
        if (!result.alias().isEmpty()) {
            String alias = normalize(result.alias()).strip();
            List<String> evidenceTokens = List.of(normalize(result.vehicleEvidence()).strip().split(" "));
            if (evidenceTokens.containsAll(List.of(alias.split(" ")))) {
                var supported = Catalog.VEHICLES.stream().filter(vehicle -> vehicle.aliases().contains(alias));
                named = Stream.concat(named.stream(), supported).distinct().toList();
                toyota86 = toyota86 || "toyota 86".equals(alias);
            }
        }
        List<Integer> statedYears = years(text);
        Match existing = match(named, statedYears, toyota86);
        Match resolved = existing;
        boolean resolvedFamily = toyota86 || named.stream().anyMatch(Catalog.TOYOTA_86_FAMILY::contains);
        if (existing.status().equals("unsupported")
                || (!resolvedFamily && (existing.status().equals("matched") || existing.status().equals("conflict")))) {
            List<OpenAiExtractor.VehicleClue> clues = result.vehicles();
            if (clues.isEmpty() && existing.status().equals("unsupported")) {
                clues = clueFromAlias(result.alias(), result.vehicleEvidence()).stream().toList();
            }
            resolved = resolveClues(existing, statedYears, vehicleWording(text), clues);
        }
        return new Extraction("live", "vehicle_extraction_v1",
                liveFacts(result),
                finishMatch(resolved, text, true), null);
    }

    private static Facts liveFacts(OpenAiExtractor.Result result) {
        var ids = result.parts().stream().map(Part::id).filter(id -> !UNCLASSIFIED.equals(id)).distinct().toList();
        return new Facts(ids, isStock(normalize(result.stockEvidence()), ids), result.parts());
    }

    private static List<String> modIds(String text) {
        return MOD_TERMS.stream().filter(terms -> mentions(text, terms.pattern())).map(ModTerms::id).toList();
    }

    private static List<Part> describeParts(String description, boolean keepUnknown) {
        return Stream.of(description.split("(?i)[,;\\r\\n]+|\\s+&\\s*|\\s+and\\s+", 12))
                .map(String::strip).filter(name -> !name.isEmpty())
                .flatMap(name -> {
                    var ids = modIds(normalize(name));
                    if (ids.isEmpty() && keepUnknown) return Stream.of(new Part(UNCLASSIFIED, name));
                    return ids.stream().map(id -> new Part(id, name));
                }).distinct().toList();
    }

    private Match resolveClues(Match existing, List<Integer> statedYears, String identityText,
                               List<OpenAiExtractor.VehicleClue> clues) {
        if (clues.isEmpty()) return existing;
        List<Integer> identityYears = years(identityText);
        var proposals = new LinkedHashSet<VehicleDetails>();
        for (var clue : clues) {
            var evidenceYears = years(clue.evidence());
            var eligibleYears = evidenceYears.isEmpty() && identityYears.size() == 1 ? identityYears : evidenceYears;
            for (int year : eligibleYears) {
                VehicleDetails.validate(clue.make(), clue.model(), year).ifPresent(proposals::add);
            }
        }
        boolean clear = existing.candidates().isEmpty() && proposals.size() == 1
                && statedYears.size() == 1 && identityYears.size() == 1 && clues.size() == 1
                && VehicleDetails.namesMake(clues.getFirst().evidence(), clues.getFirst().make())
                && !hasConflictingMake(identityText, clues.getFirst().make());
        var candidates = new LinkedHashMap<String, VehicleOption>();
        existing.candidates().forEach(vehicle -> candidates.put(vehicle.id(), vehicle));
        var suggestions = new LinkedHashMap<String, VehicleOption>();
        for (var details : proposals) {
            VehicleOption option = optionFor(details, clear);
            candidates.putIfAbsent(option.id(), option);
            if (option.referenceMatch() == VehicleReference.Status.UNLISTED) {
                VehicleReference.match(details).models().forEach(model -> {
                    var suggestion = optionFor(new VehicleDetails(details.make(), model, details.year()), false);
                    suggestions.putIfAbsent(suggestion.id(), suggestion);
                });
            }
        }
        if (candidates.isEmpty()) {
            var hints = clues.stream().map(clue -> new VehicleHint(clue.make(), clue.model())).distinct().toList();
            return new Match("incomplete", List.of(), hints.size() == 1 ? hints.getFirst() : null);
        }
        if (candidates.values().stream().anyMatch(CatalogExtractor::requiresReferenceConfirmation)) {
            suggestions.values().stream().filter(option -> !candidates.containsKey(option.id())).limit(2)
                    .toList().forEach(option -> candidates.put(option.id(), option));
            return new Match("unverified", List.copyOf(candidates.values()));
        }
        boolean exact = candidates.size() == 1 && statedYears.size() <= 1
                && (existing.status().equals("matched") || (clues.size() == 1
                    && VehicleDetails.namesMake(clues.getFirst().evidence(), clues.getFirst().make())));
        return new Match(exact ? "matched" : "ambiguous", List.copyOf(candidates.values()));
    }

    private static boolean requiresReferenceConfirmation(VehicleOption option) {
        return option.referenceMatch() != null && !option.referenceMatch().verified();
    }

    private static Optional<OpenAiExtractor.VehicleClue> clueFromAlias(String alias, String evidence) {
        String phrase = VehicleDetails.words(alias);
        if (phrase.isEmpty() || !normalize(evidence).contains(" " + phrase + " ")) return Optional.empty();
        // Prefer the full make over a shorter alias when both prefixes match.
        for (int end = alias.lastIndexOf(' '); end > 0; end = alias.lastIndexOf(' ', end - 1)) {
            var make = VehicleDetails.canonicalMake(alias.substring(0, end));
            String model = alias.substring(end + 1).strip();
            if (make.isPresent() && VehicleDetails.validModel(model)) {
                return Optional.of(new OpenAiExtractor.VehicleClue(make.get(), model, evidence));
            }
        }
        return Optional.empty();
    }

    private VehicleOption optionFor(VehicleDetails details, boolean checkVpic) {
        String model = VehicleDetails.words(details.model());
        String identity = VehicleDetails.words(details.make()) + " " + model;
        var known = Catalog.VEHICLES.stream()
                .filter(vehicle -> vehicle.make().equals(details.make()) && vehicle.year() == details.year())
                .filter(vehicle -> vehicle.aliases().contains(identity) || vehicle.aliases().contains(model)).toList();
        if (known.size() == 1 && !checkVpic) return known.getFirst().option();
        var reference = checkVpic ? lookup.match(details) : VehicleReference.match(details);
        if (known.size() == 1) {
            var status = reference.status() == VehicleReference.Status.VPIC_EXACT ? reference.status() : null;
            return known.getFirst().option(status);
        }
        if (reference.status().verified()) {
            var canonical = new VehicleDetails(details.make(), reference.models().getFirst(), details.year());
            return canonical.option(reference.status());
        }
        return details.option(reference.status());
    }

    private Match finishMatch(Match match, String text, boolean checkVpic) {
        if (!match.status().equals("matched")) return match;
        String vehicleText = vehicleWording(text);
        var vehicle = match.candidates().getFirst();
        if (hasConflictingMake(vehicleText, vehicle.make())) return new Match("ambiguous", match.candidates());
        if (!checkVpic || vehicle.entered() != null || vehicle.referenceMatch() != null
                || !VehicleDetails.namesMake(vehicleText, vehicle.make())) return match;
        var details = new VehicleDetails(vehicle.make(), vehicle.model(), vehicle.year());
        return new Match("matched", List.of(optionFor(details, true)));
    }

    private static boolean hasConflictingMake(String vehicleText, String resolvedMake) {
        return VehicleDetails.MAKES.stream().anyMatch(make ->
                !make.equals(resolvedMake) && VehicleDetails.namesMake(vehicleText, make));
    }

    private static String vehicleWording(String text) {
        return text.split("\\b(?:with|has|running)\\b", 2)[0];
    }

    private static boolean isStock(String text, List<String> modIds) {
        return modIds.stream().allMatch("misc"::equals)
                && (text.equals(" stock ") || text.contains(" all stock ") || text.contains(" completely stock ")
                    || text.contains(" no mods "));
    }

    private static List<Vehicle> namedVehicles(String text) {
        return Catalog.VEHICLES.stream()
                .filter(vehicle -> vehicle.aliases().stream().anyMatch(alias -> text.contains(" " + alias + " ")))
                .toList();
    }

    private static List<Integer> years(String text) {
        return YEAR.matcher(text).results().map(match -> Integer.parseInt(match.group())).distinct().toList();
    }

    private static String normalize(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip() + " ";
    }

    private Match match(List<Vehicle> named, List<Integer> years, boolean toyota86) {
        if (toyota86 && named.stream().noneMatch(Catalog.TOYOTA_86_FAMILY::contains)) {
            var family = Catalog.TOYOTA_86_FAMILY;
            var candidates = family.stream().filter(vehicle -> years.contains(vehicle.year())).toList();
            if (years.isEmpty()) candidates = family;
            if (!named.isEmpty()) {
                return matchOf("conflict", Stream.concat(named.stream(), candidates.stream()).toList());
            }
            if (years.size() > 1) return matchOf("conflict", candidates.isEmpty() ? family : candidates);
            if (candidates.isEmpty()) return matchOf("unsupported", candidates);
            return matchOf(candidates.size() == 1 ? "matched" : "ambiguous", candidates);
        }
        if (named.isEmpty()) return new Match("unsupported", List.of());

        boolean multipleModels = named.stream().map(vehicle -> List.of(vehicle.make(), vehicle.model()))
                .collect(Collectors.toSet()).size() > 1;
        if (multipleModels || years.size() > 1) return matchOf("conflict", named);
        if (years.isEmpty()) return matchOf(named.size() > 1 ? "ambiguous" : "incomplete", named);

        List<Vehicle> candidates = named.stream().filter(vehicle -> vehicle.year() == years.getFirst()).toList();
        return matchOf(candidates.isEmpty() ? "unsupported" : "matched", candidates);
    }

    private Match matchOf(String status, List<Vehicle> vehicles) {
        return new Match(status, vehicles.stream().map(Vehicle::option).toList());
    }

    private static boolean mentions(String text, Pattern pattern) {
        return pattern.matcher(text).results()
                .anyMatch(match -> !EXCLUSION.matcher(text.substring(0, match.start())).find());
    }

    private static Example example(String id, String text) {
        return new Example(id, text, text);
    }

    private record ModTerms(String id, Pattern pattern) {
    }

    public record Example(String id, String label, String description) {
    }

    public record Part(String id, String label) {
    }

    public record Facts(List<String> modIds, boolean stockMention,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Part> parts) {
        Facts(List<String> modIds, boolean stockMention) {
            this(modIds, stockMention, List.of());
        }
    }

    public record VehicleHint(String make, String model) {
    }

    public record Match(String status, List<VehicleOption> candidates,
                        @JsonInclude(JsonInclude.Include.NON_NULL) VehicleHint hint) {
        Match(String status, List<VehicleOption> candidates) {
            this(status, candidates, null);
        }
    }

    public record Extraction(String source, String schemaVersion, Facts facts, Match match,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackReason) {
        Extraction withFallback(String reason) {
            return new Extraction(source, schemaVersion, facts, match, reason);
        }
    }
}
