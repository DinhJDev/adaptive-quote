package dev.dinhj.adaptivequote.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public record VehicleDetails(@NotBlank @Size(max = 32) String make,
                             @NotBlank @Size(max = 48) String model,
                             @NotNull Integer year) {
    public static final int FIRST_YEAR = 1886;
    public static final int LAST_YEAR = Year.now(ZoneOffset.UTC).getValue() + 1;
    public static final List<String> MAKES = List.of(
            "Acura", "Audi", "BMW", "Buick", "Cadillac", "Chevrolet", "Chrysler", "Dodge", "Ford", "Genesis",
            "GMC", "Honda", "Hyundai", "Infiniti", "Jeep", "Kia", "Lexus", "Lincoln", "Mazda", "Mercedes-Benz",
            "MINI", "Mitsubishi", "Nissan", "Porsche", "Ram", "Subaru", "Toyota", "Volkswagen", "Volvo");
    private static final Map<String, String> MAKE_ALIASES = Map.of(
            "chevy", "Chevrolet", "vw", "Volkswagen", "volkswagon", "Volkswagen",
            "mercedes", "Mercedes-Benz", "benz", "Mercedes-Benz");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 .+/&'()-]{0,47}");

    public static Optional<String> canonicalMake(String value) {
        if (value == null) return Optional.empty();
        String name = words(value);
        return MAKES.stream().filter(make -> words(make).equals(name)).findFirst()
                .or(() -> Optional.ofNullable(MAKE_ALIASES.get(name)));
    }

    public static boolean namesMake(String text, String make) {
        String normalized = " " + words(text) + " ";
        return normalized.contains(" " + words(make) + " ")
                || MAKE_ALIASES.entrySet().stream().anyMatch(alias -> alias.getValue().equals(make)
                        && normalized.contains(" " + alias.getKey() + " "));
    }

    public static boolean validModel(String model) {
        return model != null && MODEL.matcher(model.strip()).matches();
    }

    public static Optional<VehicleDetails> validate(String make, String model, Integer year) {
        if (!validModel(model) || year == null || year < FIRST_YEAR || year > LAST_YEAR) return Optional.empty();
        return canonicalMake(make).map(name -> new VehicleDetails(name, model.strip().replaceAll(" +", " "), year));
    }

    public String id() {
        String key = make + "\n" + model.toLowerCase(Locale.ROOT) + "\n" + year;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "entered-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public Catalog.VehicleOption option(VehicleReference.Status referenceMatch) {
        return new Catalog.VehicleOption(id(), year + " " + make + " " + model, make, model, year, true, referenceMatch);
    }

    public static String words(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
