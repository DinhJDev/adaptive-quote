package dev.dinhj.adaptivequote.catalog;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class VpicClient {
    private static final Logger LOG = LoggerFactory.getLogger(VpicClient.class);
    private static final URI ENDPOINT = URI.create("https://vpic.nhtsa.dot.gov/api/vehicles/");
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);
    private static final int FIRST_LOOKUP_YEAR = 1996;

    private final boolean enabled;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final Semaphore outbound = new Semaphore(2);
    private final Map<MakeYear, Batch> cache = new ConcurrentHashMap<>();

    @Autowired
    public VpicClient(@Value("${quote.vpic.enabled}") boolean enabled, ObjectMapper json) {
        this(enabled, json, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), ENDPOINT);
    }

    VpicClient(boolean enabled, ObjectMapper json, HttpClient http, URI endpoint) {
        this.enabled = enabled;
        this.json = json;
        this.http = http;
        this.endpoint = endpoint;
    }

    public Optional<String> findExact(VehicleDetails vehicle) {
        if (!enabled || vehicle.year() == null || vehicle.year() < FIRST_LOOKUP_YEAR || vehicle.year() > VehicleDetails.LAST_YEAR
                || !VehicleDetails.MAKES.contains(vehicle.make())) return Optional.empty();

        var key = new MakeYear(vehicle.make(), vehicle.year());
        var batch = cache.compute(key, (requested, previous) -> {
            if (previous != null && previous.expiresAt().isAfter(Instant.now())) return previous;
            if (!outbound.tryAcquire()) return previous;
            try {
                return fetch(requested);
            } finally {
                outbound.release();
            }
        });
        if (batch == null || !batch.expiresAt().isAfter(Instant.now())) return Optional.empty();
        return Optional.ofNullable(batch.models().get(VehicleDetails.words(vehicle.model())));
    }

    private Batch fetch(MakeYear key) {
        String make = key.make().toLowerCase(Locale.ROOT);
        URI uri = endpoint.resolve("GetModelsForMakeYear/make/" + make + "/modelyear/" + key.year() + "?format=json");
        var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                LOG.debug("vPIC lookup returned HTTP {}", response.statusCode());
                return failed();
            }
            var root = json.readTree(response.body());
            if (root == null) return failed();
            var results = root.path("Results");
            if (!results.isArray()) return failed();
            Map<String, String> models = new HashMap<>();
            for (var row : results) {
                String makeName = row.path("Make_Name").stringValue();
                String modelName = row.path("Model_Name").stringValue();
                if (VehicleDetails.canonicalMake(makeName).filter(key.make()::equals).isPresent()
                        && VehicleDetails.validModel(modelName)) {
                    models.putIfAbsent(VehicleDetails.words(modelName), modelName);
                }
            }
            return new Batch(Map.copyOf(models), Instant.now().plus(CACHE_TTL));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed();
        } catch (ExecutionException | TimeoutException | JacksonException ex) {
            LOG.debug("vPIC lookup could not complete ({})", ex.getClass().getSimpleName());
            return failed();
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static Batch failed() {
        return new Batch(Map.of(), Instant.now().plus(FAILURE_TTL));
    }

    @PreDestroy
    void close() {
        http.shutdownNow();
    }

    private record MakeYear(String make, int year) {
    }

    private record Batch(Map<String, String> models, Instant expiresAt) {
    }
}
