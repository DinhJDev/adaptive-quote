package dev.dinhj.adaptivequote.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VpicClientTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private HttpServer server;
    private VpicClient vpic;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        vpic = new VpicClient(true, JsonMapper.builder().build(), HttpClient.newHttpClient(), endpoint);
    }

    @AfterEach
    void stop() {
        vpic.close();
        server.stop(0);
    }

    @Test
    void checksAnExactModelAndReusesTheSameMakeAndYearResponse() {
        respond(200, """
                {"Count":2,"Results":[
                  {"Make_Name":"BMW","Model_Name":"335i"},
                  {"Make_Name":"NOT BMW","Model_Name":"Special"}]}
                """);
        var lookup = new VehicleLookup(vpic);
        assertThat(lookup.match(new VehicleDetails("BMW", "335i", 2008)).status())
                .isEqualTo(VehicleReference.Status.VPIC_EXACT);
        assertThat(lookup.match(new VehicleDetails("BMW", "335", 2008)).status())
                .isEqualTo(VehicleReference.Status.UNLISTED);
        assertThat(lookup.match(new VehicleDetails("BMW", "Special", 2008)).status())
                .isEqualTo(VehicleReference.Status.UNLISTED);
        assertThat(requests).hasValue(1);
        assertThat(requestUri).hasValue("/GetModelsForMakeYear/make/bmw/modelyear/2008?format=json");
    }

    @Test
    void prefersAnExactVpicMatchWhenTheBundledReferenceAlsoListsTheModel() {
        respond(200, """
                {"Count":1,"Results":[{"Make_Name":"Honda","Model_Name":"Civic"}]}
                """);
        var lookup = new VehicleLookup(vpic);
        var match = lookup.match(new VehicleDetails("Honda", "Civic", 2019));
        assertThat(match.status()).isEqualTo(VehicleReference.Status.VPIC_EXACT);
        assertThat(match.models()).containsExactly("Civic");
        assertThat(requests).hasValue(1);
    }

    @Test
    void keepsTheBundledReferenceWhenVpicIsUnavailable() {
        respond(503, "{}");
        var lookup = new VehicleLookup(vpic);
        assertThat(lookup.match(new VehicleDetails("Ford", "Custom Hot Rod", 1930)).status())
                .isEqualTo(VehicleReference.Status.UNLISTED);
        assertThat(requests).hasValue(0);
        assertThat(lookup.match(new VehicleDetails("Honda", "Civic", 2019)).status())
                .isEqualTo(VehicleReference.Status.NAMEPLATE);
        assertThat(lookup.match(new VehicleDetails("BMW", "335i", 2008)).status())
                .isEqualTo(VehicleReference.Status.UNLISTED);
        assertThat(vpic.findExact(new VehicleDetails("BMW", "335i", 2008))).isEmpty();
        assertThat(requests).hasValue(2);
    }

    private void respond(int status, String text) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestUri.set(exchange.getRequestURI().toString());
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }
}
