package dev.dinhj.adaptivequote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.catalog.VehicleLookup;
import dev.dinhj.adaptivequote.catalog.VpicClient;
import dev.dinhj.adaptivequote.extraction.CatalogExtractor;
import dev.dinhj.adaptivequote.extraction.LiveExtraction;
import dev.dinhj.adaptivequote.quote.QuotePricing;
import dev.dinhj.adaptivequote.web.QuoteApi;
import dev.dinhj.adaptivequote.web.QuoteSessions;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = QuoteApi.class, properties = "quote.public-origin=http://localhost:8080")
@Import({CatalogExtractor.class, LiveExtraction.class, QuotePricing.class, QuoteSessions.class, VehicleLookup.class})
class QuoteFlowTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private VpicClient vpic;

    @Test
    void catalogOffersAllThreeToyota86GenerationsAndDisablesLiveIntake() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[*].id",
                        hasItems("toyota-ae86-1986", "toyota-gt86-2020", "toyota-gr86-2022")))
                .andExpect(jsonPath("$.vehicleReferenceVersion").value("2026.11.10"))
                .andExpect(jsonPath("$.liveEnabled").value(false));
    }

    @Test
    void clearExampleFindsOneVehicleAndTheIntake() throws Exception {
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"fixture\",\"scenarioId\":\"clear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("fixture"))
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].id").value("toyota-gr86-2022"))
                .andExpect(jsonPath("$.facts.modIds[0]").value("intake"));
    }

    @Test
    void ambiguousExampleOffersThe86FamilyWhenTheYearIsMissing() throws Exception {
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"fixture\",\"scenarioId\":\"ambiguous\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("ambiguous"))
                .andExpect(jsonPath("$.match.candidates[*].id",
                        hasItems("toyota-ae86-1986", "toyota-gt86-2021", "toyota-gr86-2022")));
    }

    @Test
    void liveRequestWithoutProviderFallsBackToTheSameDescription() throws Exception {
        mvc.perform(post("/api/v1/extractions").header(HttpHeaders.ORIGIN, "http://localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"live","text":"2000 Nissan GTR with an exhaust"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("catalog"))
                .andExpect(jsonPath("$.fallbackReason").value("unavailable"))
                .andExpect(jsonPath("$.match.candidates[0].id").value("nissan-skyline-gtr-2000"))
                .andExpect(jsonPath("$.facts.modIds[0]").value("exhaust"));
    }

    @Test
    void typedIntakeNormalizesMakeAndPreservesStatedVehicleYear() throws Exception {
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"catalog\",\"text\":\"2020 VW GTI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("catalog"))
                .andExpect(jsonPath("$.match.candidates[0].make").value("Volkswagen"))
                .andExpect(jsonPath("$.match.candidates[0].year").value(2020));
    }

    @Test
    void modificationInputPreservesPartNamesAndFlagsUnrecognizedParts() throws Exception {
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"catalog","scope":"modifications",
                                 "text":"BC Coilovers, Apex'i Single Exit Exhaust, unknown black box"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.modIds", contains("exhaust", "misc")))
                .andExpect(jsonPath("$.facts.parts[*].id", contains("misc", "exhaust", "unclassified")))
                .andExpect(jsonPath("$.facts.parts[*].label",
                        contains("BC Coilovers", "Apex'i Single Exit Exhaust", "unknown black box")));
        verifyNoInteractions(vpic);
    }

    @ParameterizedTest
    @CsvSource({
            "1986 86, toyota-ae86-1986",
            "2017 Toyota 86, toyota-gt86-2017",
            "2020 86 with an exhaust, toyota-gt86-2020",
            "2021 GT86, toyota-gt86-2021",
            "2022 86, toyota-gr86-2022",
            "2023 86, toyota-gr86-2023",
            "2027 86, toyota-gr86-2027"
    })
    void resolvesThe86GenerationUsingTheYear(String description, String expectedId) throws Exception {
        extract(description).andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].id").value(expectedId));
    }

    @Test
    void resolves2021ShorthandToGt86AndKeepsModifications() throws Exception {
        extract("2021 86 with BC Coilovers & Apex'i Single Exit Exhaust")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[*].id",
                        contains("toyota-gt86-2021")))
                .andExpect(jsonPath("$.facts.modIds", contains("exhaust", "misc")));
        verifyNoInteractions(vpic);
    }

    @Test
    void explicitlyEntered2021Gr86CanContinueToCustomPricing() throws Exception {
        var vehicle = new VehicleDetails("Toyota", "GR86", 2021);
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"manual","vehicle":{"make":"Toyota","model":"GR86","year":2021}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.candidates[0].id").value(vehicle.id()))
                .andExpect(jsonPath("$.match.candidates[0].referenceMatch").value("year_outside_range"));
        mvc.perform(preview("""
                {"vehicleId":"%s","vehicleDetails":{"make":"Toyota","model":"GR86","year":2021},
                 "modStatus":"stock","modIds":[],"serviceId":null,"addOnIds":[]}
                """.formatted(vehicle.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customReviewReason").value("unverified_vehicle"));
    }

    @Test
    void clearNamedVehicleGetsVpicSourceWithoutChangingTheDemoVehicleId() throws Exception {
        when(vpic.findExact(new VehicleDetails("Toyota", "GR86", 2022))).thenReturn(Optional.of("GR86"));
        extract("2022 Toyota GR86 with an intake").andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].id").value("toyota-gr86-2022"))
                .andExpect(jsonPath("$.match.candidates[0].referenceMatch").value("vpic_exact"))
                .andExpect(jsonPath("$.facts.modIds[0]").value("intake"));
    }

    @Test
    void conflictingExplicitGenerationDoesNotFallThroughToAnotherGeneration() throws Exception {
        extract("2020 Toyota GR86").andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("unsupported"));
    }

    @Test
    void unrelatedNumberIsNotAVehicleAlias() throws Exception {
        extract("2020 VW GTI has 86 mm tips").andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].id").value("volkswagen-gti-2020"));
    }

    @Test
    void typedExamplesUseCanonicalNissanAndFordLabels() throws Exception {
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"catalog\",\"text\":\"2000 Nissan GTR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.candidates[0].label").value("2000 Nissan Skyline GT-R"));
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"catalog\",\"text\":\"2022 Ford Mustang GT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.candidates[0].label").value("2022 Ford Mustang GT"));
    }

    @Test
    void springPricesSelectedTuneAndAddon() throws Exception {
        mvc.perform(preview("""
                {"vehicleId":"toyota-gr86-2022","modStatus":"modified","modIds":["intake"],
                 "serviceId":"tune","addOnIds":["log-review"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor").value(44800))
                .andExpect(jsonPath("$.lineItems", hasSize(2)));
    }

    @Test
    void pricingRejectsAClientSuppliedTotal() throws Exception {
        mvc.perform(preview("""
                {"vehicleId":"toyota-gr86-2022","modStatus":"stock","modIds":[],
                 "serviceId":"tune","addOnIds":[],"totalMinor":1}
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pricingRejectsAnIneligibleAddon() throws Exception {
        mvc.perform(preview("""
                {"vehicleId":"toyota-gr86-2022","modStatus":"stock","modIds":[],
                 "serviceId":"tune","addOnIds":["log-review"]}
                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_SELECTION"));
    }

    @Test
    void unknownModificationsOfferAssessmentOnly() throws Exception {
        mvc.perform(preview("""
                {"vehicleId":"ford-mustang-gt-2022","modStatus":"unsure","modIds":[],
                 "serviceId":null,"addOnIds":[]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleServices", hasSize(1)))
                .andExpect(jsonPath("$.eligibleServices[0].id").value("assessment"));
    }

    @Test
    void vintageToyotaRoutesToCustomPricing() throws Exception {
        mvc.perform(preview("""
                {"vehicleId":"toyota-ae86-1986","modStatus":"modified","modIds":["exhaust"],
                 "serviceId":null,"addOnIds":[]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customReviewReason").value("older_vehicle"))
                .andExpect(jsonPath("$.eligibleServices", hasSize(0)))
                .andExpect(jsonPath("$.totalMinor").isEmpty());
    }

    @Test
    void manualClassicIdentifiesTheVehicleAndRoutesToTunerReview() throws Exception {
        var vehicle = new VehicleDetails("Ford", "Model A", 1930);
        mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"mode":"manual","vehicle":{"make":"Ford","model":"Model A","year":1930}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.candidates[0].id").value(vehicle.id()))
                .andExpect(jsonPath("$.match.candidates[0].label").value("1930 Ford Model A"));
        mvc.perform(preview("""
                {"vehicleId":"%s","vehicleDetails":{"make":"Ford","model":"Model A","year":1930},
                 "modStatus":"modified","modIds":["other-performance"],"serviceId":"tune","addOnIds":[]}
                """.formatted(vehicle.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customReviewReason").value("older_vehicle"))
                .andExpect(jsonPath("$.totalMinor").isEmpty());
    }

    @Test
    void calculatesPriceFromMakeYearAndModWeightWithServerValidatedDetails() throws Exception {
        var vehicle = new VehicleDetails("BMW", "X5", 2008);
        String body = """
                {"vehicleId":"%s","vehicleDetails":{"make":"BMW","model":"X5","year":2008},
                 "modStatus":"modified","modIds":["intake","intercooler","misc"],"serviceId":"tune","addOnIds":[]}
                """.formatted(vehicle.id());
        mvc.perform(preview(body)).andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(67_400));
        mvc.perform(preview(body.replace("2008", "2020"))).andExpect(status().isUnprocessableContent());
        mvc.perform(preview(body.replace("\"intake\",\"intercooler\",\"misc\"", "\"turbo\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customReviewReason").value("complex_build"));
    }

    @Test
    void offlineExtractionChecksANameplateOutsideTheDemoExamples() throws Exception {
        extract("2019 Honda Civic with an exhaust").andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].label").value("2019 Honda Civic"))
                .andExpect(jsonPath("$.match.candidates[0].referenceMatch").value("nameplate"))
                .andExpect(jsonPath("$.facts.modIds[0]").value("exhaust"));
    }

    @ParameterizedTest
    @CsvSource({"BMW, 335i, 2008", "Ford, Fiesta, 2026"})
    void checksTheReferenceServerSideBeforeOfferingAnEstimate(String make, String model, int year) throws Exception {
        var vehicle = new VehicleDetails(make, model, year);
        mvc.perform(preview("""
                {"vehicleId":"%s","vehicleDetails":{"make":"%s","model":"%s","year":%d},
                 "modStatus":"stock","modIds":[],"serviceId":"tune","addOnIds":[]}
                """.formatted(vehicle.id(), make, model, year)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customReviewReason").value("unverified_vehicle"))
                .andExpect(jsonPath("$.eligibleServices", hasSize(0)))
                .andExpect(jsonPath("$.totalMinor").isEmpty());
    }

    @Test
    void exactVpicMatchCanBeExtractedAndPricedWithTheSameVehicle() throws Exception {
        var vehicle = new VehicleDetails("BMW", "335i", 2008);
        when(vpic.findExact(vehicle)).thenReturn(Optional.of("335i"));
        extract("2008 BMW 335i with an intake and intercooler").andExpect(status().isOk())
                .andExpect(jsonPath("$.match.status").value("matched"))
                .andExpect(jsonPath("$.match.candidates[0].id").value(vehicle.id()))
                .andExpect(jsonPath("$.match.candidates[0].referenceMatch").value("vpic_exact"));
        mvc.perform(preview("""
                {"vehicleId":"%s","vehicleDetails":{"make":"BMW","model":"335i","year":2008},
                 "modStatus":"modified","modIds":["intake","intercooler"],"serviceId":"tune","addOnIds":[]}
                """.formatted(vehicle.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(67400));
    }

    @Test
    void theQuoteRecommendsAStageFromConfirmedModsAndPricesAnAlternateStage() throws Exception {
        String selection = """
                {"vehicleId":"toyota-gr86-2022","modStatus":"modified","modIds":["intake","exhaust","misc"],
                 "serviceId":"stage-3","addOnIds":[]}
                """;
        mvc.perform(preview(selection)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedServiceId").value("stage-2"))
                .andExpect(jsonPath("$.lineItems[0].id").value("stage-3"))
                .andExpect(jsonPath("$.totalMinor").value(64900));
        mvc.perform(preview(selection.replace("\"stage-3\"", "\"stage-2\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(52400));
    }

    private MockHttpServletRequestBuilder preview(String body) {
        return post("/api/v1/quotes/preview").header(HttpHeaders.ORIGIN, "http://localhost:8080")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions extract(String description) throws Exception {
        return mvc.perform(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"catalog","text":"%s"}
                        """.formatted(description)));
    }
}
