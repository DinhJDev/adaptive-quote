package dev.dinhj.adaptivequote;

import dev.dinhj.adaptivequote.catalog.VehicleDetails;
import dev.dinhj.adaptivequote.extraction.ExtractionBudget;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "quote.public-origin=http://localhost:8080",
        "quote.session-secret=integration-test-only-quote-signing-secret",
        "quote.vpic.enabled=false"
})
@AutoConfigureMockMvc
class QuotePersistenceIT {
    private static final String ORIGIN = "http://localhost:8080";
    private static final String SELECTION = """
            {"vehicleId":"toyota-gr86-2022","modStatus":"modified","modIds":["intake","exhaust"],
             "serviceId":"tune","addOnIds":["log-review"]}
            """;
    private static final String SAVE = """
            {"catalogVersion":"demo-7","confirmed":true,"selection":%s}
            """.formatted(SELECTION);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ExtractionBudget budget;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String external = System.getenv("QUOTE_TEST_DB_URL");
        if (external != null) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> System.getenv("QUOTE_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("QUOTE_TEST_DB_PASSWORD"));
        } else {
            PostgreSQLContainer postgres = Container.INSTANCE;
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    private static class Container {
        private static final PostgreSQLContainer INSTANCE = new PostgreSQLContainer("postgres:18.3");
        static {
            INSTANCE.start();
        }
    }

    @Test
    void shareLinkUsesPublicOriginAndReopensWithoutTheCreatingSession() throws Exception {
        String request = """
                {"selection":%s,"parts":[{"id":"exhaust","label":"Apex’i Single Exit Exhaust"}]}
                """.formatted(SELECTION);
        String location = mvc.perform(post("/api/v1/quote-links").cookie(session())
                        .header(HttpHeaders.ORIGIN, ORIGIN).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", startsWith(ORIGIN + "/?quote=")))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.vehicle.label").value("2022 Toyota GR86"))
                .andExpect(jsonPath("$.parts[0].label").value("Apex’i Single Exit Exhaust"))
                .andExpect(jsonPath("$.recommendation").value("Stage 2 calibration tune"))
                .andExpect(jsonPath("$.totalMinor").doesNotExist());
        jdbc.sql("UPDATE quote_links SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", id).update();
        mvc.perform(get(location)).andExpect(status().isNotFound());
    }

    @Test
    void shareLinkRejectsForeignOriginsAndContactDetails() throws Exception {
        String request = """
                {"selection":%s,"parts":[{"id":"exhaust","label":"Part inquiry demo@example.com"}]}
                """.formatted(SELECTION);
        Cookie owner = session();
        mvc.perform(post("/api/v1/quote-links").cookie(owner)
                        .header(HttpHeaders.ORIGIN, "https://example.com").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/quote-links").cookie(owner)
                        .header(HttpHeaders.ORIGIN, ORIGIN).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void savesReopensAndReplaysTheOriginalReceipt() throws Exception {
        Cookie owner = session();
        UUID quoteId = UUID.randomUUID();
        String saved = save(quoteId, owner, SAVE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalMinor").value(44800))
                .andExpect(jsonPath("$.checkoutExample.line_items[1].price_data.unit_amount").value(4900))
                .andReturn().getResponse().getContentAsString();

        assertThat(save(quoteId, owner, SAVE.replace("\"intake\",\"exhaust\"", "\"exhaust\",\"intake\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(saved);
        assertThat(mvc.perform(get("/api/v1/quotes/{id}", quoteId).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo(saved);
        assertThat(rows(quoteId)).isEqualTo(1);
    }

    @Test
    void concurrentSavesInsertOneRow() throws Exception {
        Cookie owner = session();
        UUID quoteId = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> save(quoteId, owner, SAVE).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> save(quoteId, owner, SAVE).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 201);
        }
        assertThat(rows(quoteId)).isEqualTo(1);
    }

    @Test
    void enteredVehicleDetailsSurviveQuoteSaveAndReplay() throws Exception {
        var vehicle = new VehicleDetails("BMW", "X5", 2008);
        String body = """
                {"catalogVersion":"demo-7","confirmed":true,"selection":{
                  "vehicleId":"%s","vehicleDetails":{"make":"BMW","model":"X5","year":2008},
                  "modStatus":"modified","modIds":["intake","intercooler"],"serviceId":"tune","addOnIds":[]}}
                """.formatted(vehicle.id());
        Cookie owner = session();
        UUID id = UUID.randomUUID();
        save(id, owner, body).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor").value(67400));
        save(id, owner, body).andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotes/{id}", id).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.selection.vehicleDetails.model").value("X5"));
        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    void concurrentExtractionReservationsRespectTheMonthlyCeilingAndRateLimit() throws Exception {
        var start = OffsetDateTime.parse("2091-01-01T00:00:00Z");
        var month = start.toLocalDate();
        jdbc.sql("DELETE FROM ai_budget WHERE month_start = :month").param("month", month).update();
        try {
            for (int i = 0; i < 13; i++) {
                assertThat(budget.reserve(month, start.plusSeconds(i * 10L))).isTrue();
            }
            assertThat(budget.reserve(month, start.plusSeconds(129))).isFalse();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> budget.reserve(month, start.plusSeconds(130)));
                var second = pool.submit(() -> budget.reserve(month, start.plusSeconds(130)));
                assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            }
            assertThat(budget.reserve(month, start.plusSeconds(140))).isFalse();
            assertThat(jdbc.sql("SELECT accounted_micros FROM ai_budget WHERE month_start = :month")
                    .param("month", month).query(Integer.class).single()).isEqualTo(3_920_000);
        } finally {
            jdbc.sql("DELETE FROM ai_budget WHERE month_start = :month").param("month", month).update();
        }
    }

    @Test
    void rejectsForeignSessionsChangedRequestsAndExpiredQuotes() throws Exception {
        Cookie owner = session();
        UUID quoteId = UUID.randomUUID();
        save(quoteId, owner, SAVE).andExpect(status().isCreated());
        Cookie other = session();
        mvc.perform(get("/api/v1/quotes/{id}", quoteId).cookie(other)).andExpect(status().isNotFound());
        save(quoteId, other, SAVE).andExpect(status().isNotFound());
        save(quoteId, owner, SAVE.replace("[\"log-review\"]", "[]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_CONFLICT"));
        jdbc.sql("UPDATE quotes SET created_at = now() - interval '8 days', expires_at = now() - interval '1 day' WHERE id = :id")
                .param("id", quoteId).update();
        mvc.perform(get("/api/v1/quotes/{id}", quoteId).cookie(owner)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnconfirmedStaleAndCrossOriginSaves() throws Exception {
        Cookie owner = session();
        UUID quoteId = UUID.randomUUID();
        save(quoteId, owner, SAVE.replace("\"confirmed\":true", "\"confirmed\":false"))
                .andExpect(status().isBadRequest());
        save(quoteId, owner, SAVE.replace("demo-7", "demo-0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATALOG_CHANGED"));
        mvc.perform(put("/api/v1/quotes/{id}", quoteId).cookie(owner)
                        .header(HttpHeaders.ORIGIN, "https://other.example")
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE))
                .andExpect(status().isForbidden());
        assertThat(rows(quoteId)).isZero();
    }

    private Cookie session() throws Exception {
        return mvc.perform(post("/api/v1/quotes/preview").header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(SELECTION))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getCookie("adaptive_quote");
    }

    private ResultActions save(UUID id, Cookie owner, String body) throws Exception {
        return mvc.perform(put("/api/v1/quotes/{id}", id).cookie(owner).header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int rows(UUID id) {
        return jdbc.sql("SELECT count(*) FROM quotes WHERE id = :id").param("id", id).query(Integer.class).single();
    }
}
