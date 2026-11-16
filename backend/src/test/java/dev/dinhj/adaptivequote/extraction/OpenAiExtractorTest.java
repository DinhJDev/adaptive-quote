package dev.dinhj.adaptivequote.extraction;

import com.openai.errors.OpenAIException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.catalog.VehicleLookup;
import dev.dinhj.adaptivequote.catalog.VehicleReference;
import dev.dinhj.adaptivequote.catalog.VpicClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiExtractorTest {
    private static final String FACTS = """
            {"vehicleAlias":"vw gti","vehicleEvidence":"GTI by VW",
             "vehicleCandidates":[],
             "modifications":[{"id":"exhaust","evidence":"aftermarket muffler"}],"stockEvidence":""}
            """;
    private static final String DESCRIPTION = "2020 GTI by VW with an aftermarket muffler";
    private final ObjectMapper json = JsonMapper.builder().build();
    private final VpicClient vpic = mock(VpicClient.class);
    private final CatalogExtractor catalogExtractor = new CatalogExtractor(new VehicleLookup(vpic));
    private HttpServer server;
    private OpenAiExtractor extractor;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var client = OpenAiExtractor.clientBuilder("fake-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
        extractor = new OpenAiExtractor(client, json);
    }

    @AfterEach
    void stop() {
        extractor.close();
        server.stop(0);
    }

    @Test
    void requestsStrictPrivateExtractionAndResolvesThroughTheCatalog() {
        var request = stub(200, completed(FACTS));
        var result = extractor.extract(DESCRIPTION).orElseThrow();
        var resolved = catalogExtractor.resolveLive(DESCRIPTION, result);

        var sent = json.readTree(request.get());
        assertThat(sent.path("store").booleanValue()).isFalse();
        assertThat(sent.path("model").stringValue()).isEqualTo("gpt-6-luna");
        assertThat(sent.path("service_tier").stringValue()).isEqualTo("default");
        assertThat(sent.path("input").stringValue()).isEqualTo(DESCRIPTION);
        assertThat(sent.path("instructions").stringValue()).contains("country_hint=US");
        assertThat(sent.path("max_output_tokens").intValue()).isEqualTo(512);
        assertThat(sent.path("tools").isMissingNode()).isTrue();
        assertThat(sent.at("/text/format/strict").booleanValue()).isTrue();
        assertThat(sent.at("/text/format/schema/additionalProperties").booleanValue()).isFalse();
        assertThat(resolved.match().candidates()).singleElement().satisfies(vehicle ->
                assertThat(vehicle.make()).isEqualTo("Volkswagen"));
        assertThat(resolved.facts().modIds()).containsExactly("exhaust");
    }

    @Test
    void rejectsPriceFields() {
        stub(200, completed(FACTS.replace("\"stockEvidence\":\"\"", "\"stockEvidence\":\"\",\"totalMinor\":1")));
        assertThat(extractor.extract(DESCRIPTION)).isEmpty();
    }

    @Test
    void rejectsModificationsWithEvidenceAbsentFromTheInput() {
        stub(200, completed(FACTS));
        assertThat(extractor.extract("2020 GTI by VW")).isEmpty();
    }

    @Test
    void leavesConflictingCatalogCandidatesForTheAppToResolve() {
        stub(200, completed(FACTS));
        String combined = DESCRIPTION + " or a Ford Mustang GT";
        var facts = extractor.extract(combined).orElseThrow();
        assertThat(catalogExtractor.resolveLive(combined, facts).match().status()).isEqualTo("conflict");
    }

    @ParameterizedTest
    @CsvSource({"1986, toyota-ae86-1986", "2021, toyota-gt86-2021", "2022, toyota-gr86-2022"})
    void groundedLiveAliasUsesTheCatalogForGenerationAndKeepsTheExtractedMods(int year, String expectedId) {
        stub(200, completed("""
                {"vehicleAlias":"86","vehicleEvidence":"%d 86",
                 "vehicleCandidates":[],
                 "modifications":[{"id":"exhaust","evidence":"Apex’i Single Exit Exhaust"},
                                  {"id":"misc","evidence":"BC coilovers"}],"stockEvidence":""}
                """.formatted(year)));
        String description = year + " 86 with BC Coilovers & Apex’i Single Exit Exhaust";
        var facts = extractor.extract(description).orElseThrow();
        var resolved = catalogExtractor.resolveLive(description, facts);
        assertThat(resolved.source()).isEqualTo("live");
        assertThat(resolved.match().status()).isEqualTo("matched");
        assertThat(resolved.match().candidates()).singleElement().satisfies(vehicle ->
                assertThat(vehicle.id()).isEqualTo(expectedId));
        assertThat(resolved.facts().modIds()).containsExactly("exhaust", "misc");
        assertThat(resolved.facts().parts()).extracting(CatalogExtractor.Part::label)
                .containsExactly("Apex’i Single Exit Exhaust", "BC coilovers");
    }

    @Test
    void liveModificationExtractionKeepsDistinctExhaustPartsInOnePricingCategory() {
        stub(200, completed("""
                {"vehicleAlias":"","vehicleEvidence":"","vehicleCandidates":[],
                 "modifications":[{"id":"exhaust","evidence":"Aftermarket headers"},
                                  {"id":"exhaust","evidence":"cat-back exhaust"}],"stockEvidence":""}
                """));
        String description = "Aftermarket headers and cat-back exhaust";
        var extraction = catalogExtractor.resolveLiveModifications(description, extractor.extract(description).orElseThrow());
        assertThat(extraction.source()).isEqualTo("live");
        assertThat(extraction.facts().modIds()).containsExactly("exhaust");
        assertThat(extraction.facts().parts()).extracting(CatalogExtractor.Part::label)
                .containsExactly("Aftermarket headers", "cat-back exhaust");
        verifyNoInteractions(vpic);
    }

    @Test
    void acceptsVehiclesOutsideTheCatalogAndRequiresConfirmationForAMakeCorrection() {
        stub(200, completed("""
                {"vehicleAlias":"","vehicleEvidence":"",
                 "vehicleCandidates":[{"make":"Toyota","model":"Supra","trim":"","evidence":"2020 BMW Supra"}],
                 "modifications":[],"stockEvidence":""}
                """));
        String description = "2020 BMW Supra";
        var resolved = catalogExtractor.resolveLive(description, extractor.extract(description).orElseThrow());
        assertThat(resolved.match().status()).isEqualTo("ambiguous");
        assertThat(resolved.match().candidates()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.label()).isEqualTo("2020 Toyota Supra");
            assertThat(vehicle.entered()).isTrue();
        });
    }

    @Test
    void offersDistinctCandidatesWhenOneModelNameCouldReferToDifferentMakes() {
        stub(200, completed("""
                {"vehicleAlias":"","vehicleEvidence":"",
                 "vehicleCandidates":[{"make":"Ford","model":"Ranger","trim":"","evidence":"2005 Ranger"},
                                      {"make":"Mazda","model":"Ranger","trim":"","evidence":"2005 Ranger"}],
                 "modifications":[],"stockEvidence":""}
                """));
        String description = "2005 Ranger";
        var resolved = catalogExtractor.resolveLive(description, extractor.extract(description).orElseThrow());
        assertThat(resolved.match().status()).isIn("ambiguous", "unverified");
        assertThat(resolved.match().candidates()).extracting(vehicle -> vehicle.make()).startsWith("Ford", "Mazda");
        verifyNoInteractions(vpic);
    }

    @Test
    void doesNotSubstituteAPartYearForAnUnstatedVehicleYear() {
        stub(200, completed("""
                {"vehicleAlias":"","vehicleEvidence":"",
                 "vehicleCandidates":[{"make":"Ford","model":"Model A","trim":"","evidence":"Ford Model A"}],
                 "modifications":[],"stockEvidence":""}
                """));
        String description = "Ford Model A with seats from a 2020 Mustang";
        var resolved = catalogExtractor.resolveLive(description, extractor.extract(description).orElseThrow());
        assertThat(resolved.match().status()).isEqualTo("incomplete");
        assertThat(resolved.match().hint()).isEqualTo(new CatalogExtractor.VehicleHint("Ford", "Model A"));
    }

    @Test
    void keepsTheStatedBmwDesignationWhenTheBroaderModelNameWasInferred() {
        stub(200, completed("""
                {"vehicleAlias":"BMW 335i","vehicleEvidence":"BMW 335i",
                 "vehicleCandidates":[{"make":"BMW","model":"3 Series","trim":"335i","evidence":"BMW 335i"}],
                 "modifications":[],"stockEvidence":""}
                """));
        String description = "2008 BMW 335i with an aftermarket intercooler";
        var facts = extractor.extract(description).orElseThrow();
        var resolved = catalogExtractor.resolveLive(description, facts);
        assertThat(resolved.match().status()).isEqualTo("unverified");
        assertThat(resolved.match().candidates()).singleElement().satisfies(vehicle ->
                assertThat(vehicle.label()).isEqualTo("2008 BMW 335i"));
        when(vpic.findExact(new VehicleDetails("BMW", "335i", 2008))).thenReturn(Optional.of("335i"));
        var verified = catalogExtractor.resolveLive(description, facts);
        assertThat(verified.match().status()).isEqualTo("matched");
        assertThat(verified.match().candidates()).singleElement().satisfies(vehicle ->
                assertThat(vehicle.referenceMatch()).isEqualTo(VehicleReference.Status.VPIC_EXACT));
    }

    @Test
    void keepsTheEvidenceAndSuggestsTheCatalogSpellingWithoutSelectingIt() {
        stub(200, completed("""
                {"vehicleAlias":"Honda Civci","vehicleEvidence":"2019 Honda Civci",
                 "vehicleCandidates":[{"make":"Honda","model":"Civci","trim":"","evidence":"2019 Honda Civci"}],
                 "modifications":[{"id":"exhaust","evidence":"aftermarket muffler"}],"stockEvidence":""}
                """));
        String description = "2019 Honda Civci with an aftermarket muffler";
        var resolved = catalogExtractor.resolveLive(description, extractor.extract(description).orElseThrow());
        assertThat(resolved.match().status()).isEqualTo("unverified");
        assertThat(resolved.match().candidates()).extracting(vehicle -> vehicle.label())
                .containsExactly("2019 Honda Civci", "2019 Honda Civic");
        assertThat(resolved.match().candidates()).extracting(vehicle -> vehicle.referenceMatch())
                .containsExactly(VehicleReference.Status.UNLISTED, VehicleReference.Status.NAMEPLATE);
        assertThat(resolved.facts().modIds()).containsExactly("exhaust");
    }

    @Test
    void keepsAnExplicitGenerationWithAnUnverifiedYearForReview() {
        stub(200, completed("""
                {"vehicleAlias":"Toyota AE86","vehicleEvidence":"1990 Toyota AE86",
                 "vehicleCandidates":[{"make":"Toyota","model":"AE86","trim":"","evidence":"1990 Toyota AE86"}],
                 "modifications":[],"stockEvidence":""}
                """));
        String description = "1990 Toyota AE86";
        var resolved = catalogExtractor.resolveLive(description, extractor.extract(description).orElseThrow());
        assertThat(resolved.match().status()).isEqualTo("unverified");
        assertThat(resolved.match().candidates()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.label()).isEqualTo(description);
            assertThat(vehicle.referenceMatch()).isEqualTo(VehicleReference.Status.YEAR_OUTSIDE_RANGE);
        });
    }

    @Test
    void returnsNoFactsOnRefusal() {
        stub(200, completedContent("""
                {"type":"refusal","refusal":"Unable to extract"}
                """));
        assertThat(extractor.extract(DESCRIPTION)).isEmpty();
    }

    @Test
    void sendsOnlyOneRequestWhenTheProviderReturnsAnError() {
        var calls = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "{}");
        });
        assertThatThrownBy(() -> extractor.extract(DESCRIPTION)).isInstanceOf(OpenAIException.class);
        assertThat(calls).hasValue(1);
    }

    private AtomicReference<String> stub(int status, String body) {
        var request = new AtomicReference<String>();
        server.createContext("/v1/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, body);
        });
        return request;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private String completed(String facts) {
        return completedContent(json.writeValueAsString(Map.of("type", "output_text", "text", facts, "annotations", new Object[0])));
    }

    private String completedContent(String content) {
        return """
                {"id":"resp_test","object":"response","model":"gpt-6-luna","status":"completed",
                 "service_tier":"default","usage":{"input_tokens":150,"output_tokens":50,"total_tokens":200},
                 "output":[{"type":"message","id":"msg_test","role":"assistant","status":"completed","content":[%s]}]}
                """.formatted(content);
    }
}
