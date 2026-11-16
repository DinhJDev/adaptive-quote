package dev.dinhj.adaptivequote.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class Catalog {
    public static final String VERSION = "demo-7";
    public static final List<Vehicle> TOYOTA_86_FAMILY = Stream.of(
            toyota86("AE86", 1983, 1987, false, List.of("ae86", "ae 86")),
            toyota86("GT86", 2012, 2021, true, List.of("gt86", "gt 86")),
            toyota86("GR86", 2022, 2027, true, List.of("gr86", "gr 86"))
    ).flatMap(stream -> stream).toList();
    public static final List<Vehicle> VEHICLES = Stream.concat(TOYOTA_86_FAMILY.stream(), Stream.of(
            new Vehicle("volkswagen-gti-2020", "Volkswagen", "Golf GTI", 2020,
                    List.of("vw gti", "vw golf gti", "volkswagen gti", "volkswagen golf gti", "golf gti"), true),
            new Vehicle("nissan-skyline-gtr-2000", "Nissan", "Skyline GT-R", 2000,
                    List.of("nissan gtr", "nissan gt r", "skyline gtr", "skyline gt r", "r34 gtr", "r34 gt r"), true),
            new Vehicle("ford-mustang-gt-2022", "Ford", "Mustang GT", 2022,
                    List.of("ford mustang gt", "mustang gt"), true)
    )).toList();
    public static final List<Modification> MODIFICATIONS = List.of(
            new Modification("intake", "Intake", "performance"),
            new Modification("exhaust", "Exhaust", "performance"),
            new Modification("intercooler", "Intercooler", "performance"),
            new Modification("turbo", "Turbo upgrade", "performance"),
            new Modification("other-performance", "Other performance modification", "performance"),
            new Modification("misc", "Cosmetic or chassis changes", "miscellaneous")
    );
    public static final List<VehicleOption> VEHICLE_OPTIONS = VEHICLES.stream()
            .map(Vehicle::option)
            .toList();

    private Catalog() {
    }

    private static Stream<Vehicle> toyota86(String generation, int start, int end, boolean tuneSupported,
                                            List<String> aliases) {
        String idPrefix = "toyota-" + generation.toLowerCase(Locale.ROOT) + "-";
        var names = aliases.stream().flatMap(alias -> Stream.of(alias, "toyota " + alias)).toList();
        return IntStream.rangeClosed(start, end)
                .mapToObj(year -> new Vehicle(idPrefix + year, "Toyota", generation, year, names, tuneSupported));
    }

    public record Vehicle(String id, String make, String model, int year, List<String> aliases, boolean tuneSupported) {
        public VehicleOption option() {
            return option(null);
        }

        public VehicleOption option(VehicleReference.Status referenceMatch) {
            return new VehicleOption(id, year + " " + make + " " + model, make, model, year, null, referenceMatch);
        }
    }

    public record VehicleOption(String id, String label, String make, String model, int year,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Boolean entered,
                                @JsonInclude(JsonInclude.Include.NON_NULL) VehicleReference.Status referenceMatch) {
    }

    public record Modification(String id, String label, String kind) {
    }
}
