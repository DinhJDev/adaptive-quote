package dev.dinhj.adaptivequote.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VehicleReference {
    public static final String VERSION = "2026.11.10";
    private static final Pattern LETTERS = Pattern.compile("[a-z]{5,}");
    private static final Map<String, List<Nameplate>> BY_MAKE = loadSnapshot();

    private VehicleReference() {
    }

    public static Match match(VehicleDetails vehicle) {
        String model = VehicleDetails.words(vehicle.model());
        if ("Toyota".equals(vehicle.make())) {
            var generation = Catalog.TOYOTA_86_FAMILY.stream()
                    .filter(entry -> entry.aliases().contains(model) || entry.aliases().contains("toyota " + model))
                    .toList();
            if (!generation.isEmpty() && generation.stream().noneMatch(entry -> entry.year() == vehicle.year())) {
                return new Match(Status.YEAR_OUTSIDE_RANGE, List.of());
            }
        }
        var nameplates = BY_MAKE.getOrDefault(vehicle.make(), List.of());
        var exact = nameplates.stream().filter(entry -> entry.names().contains(model)).toList();
        if (!exact.isEmpty()) {
            var inRange = exact.stream().filter(entry -> entry.supportsYear(vehicle.year())).toList();
            if (inRange.isEmpty()) return new Match(Status.YEAR_OUTSIDE_RANGE, List.of());
            var matches = inRange.stream().map(Nameplate::model).distinct().sorted().toList();
            var canonical = matches.stream().filter(name -> VehicleDetails.words(name).equals(model)).findFirst();
            if (canonical.isPresent()) return new Match(Status.NAMEPLATE, List.of(canonical.get()));
            if (matches.size() == 1) return new Match(Status.NAMEPLATE, matches);
            return new Match(Status.UNLISTED, matches.stream().limit(2).toList());
        }

        var suggestions = nameplates.stream().filter(entry -> entry.supportsYear(vehicle.year()))
                .filter(entry -> entry.names().stream().anyMatch(alias -> model.startsWith(alias + " ")
                        || nearSpelling(model, alias)))
                .map(Nameplate::model).distinct().sorted().limit(2).toList();
        return new Match(Status.UNLISTED, suggestions);
    }

    private static boolean nearSpelling(String model, String alias) {
        if (!LETTERS.matcher(model).matches() || !LETTERS.matcher(alias).matches()
                || Math.abs(model.length() - alias.length()) > 1) return false;
        int shared = 0;
        int shorter = Math.min(model.length(), alias.length());
        while (shared < shorter && model.charAt(shared) == alias.charAt(shared)) shared++;
        if (model.length() == alias.length()) {
            if (shared == shorter) return false;
            return model.regionMatches(shared + 1, alias, shared + 1, shorter - shared - 1)
                    || (shared + 1 < shorter && model.charAt(shared) == alias.charAt(shared + 1)
                        && model.charAt(shared + 1) == alias.charAt(shared)
                        && model.regionMatches(shared + 2, alias, shared + 2, shorter - shared - 2));
        }
        String longer = model.length() > alias.length() ? model : alias;
        String other = model.length() > alias.length() ? alias : model;
        return longer.regionMatches(shared + 1, other, shared, shorter - shared);
    }

    private static Map<String, List<Nameplate>> loadSnapshot() {
        String path = "/catalog/vehiclesdb-" + VERSION + ".tsv";
        var input = Objects.requireNonNull(VehicleReference.class.getResourceAsStream(path), path);
        Map<String, List<Nameplate>> byMake = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String row; (row = reader.readLine()) != null; ) {
                if (row.startsWith("#")) continue;
                var fields = row.split("\t", -1);
                if (fields.length != 5) throw new IllegalStateException("Invalid vehicle reference row");
                if (!VehicleDetails.validModel(fields[1])) continue;
                VehicleDetails.canonicalMake(fields[0]).ifPresent(make -> {
                    var aliases = Stream.concat(Stream.of(fields[1]), Arrays.stream(fields[2].split("\\|")))
                            .map(VehicleDetails::words).filter(alias -> !alias.isEmpty()).distinct().toList();
                    var entry = new Nameplate(fields[1], aliases, year(fields[3]), year(fields[4]));
                    byMake.computeIfAbsent(make, ignored -> new ArrayList<>()).add(entry);
                });
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Reading the pinned vehicle reference", ex);
        }
        return byMake.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue())));
    }

    private static Integer year(String value) {
        return value.matches("[0-9]{4}") ? Integer.valueOf(value) : null;
    }

    private record Nameplate(String model, List<String> names, Integer firstYear, Integer lastYear) {
        boolean supportsYear(int year) {
            // VehiclesDB's registry-based year boundaries are approximate to one year.
            return (firstYear == null || year >= firstYear - 1) && (lastYear == null || year <= lastYear + 1);
        }
    }

    public record Match(Status status, List<String> models) {
    }

    public enum Status {
        NAMEPLATE, VPIC_EXACT, UNLISTED, YEAR_OUTSIDE_RANGE;

        public boolean verified() {
            return this == NAMEPLATE || this == VPIC_EXACT;
        }

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
