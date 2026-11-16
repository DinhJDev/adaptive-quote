package dev.dinhj.adaptivequote.extraction;

import dev.dinhj.adaptivequote.catalog.VehicleLookup;
import dev.dinhj.adaptivequote.catalog.VpicClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LiveExtractionTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-16T12:00:00Z");
    private static final LocalDate MONTH = NOW.toLocalDate().withDayOfMonth(1);
    private static final String DESCRIPTION = "2020 VW GTI with an aftermarket muffler";
    private final OpenAiExtractor provider = mock(OpenAiExtractor.class);
    private final ExtractionBudget budget = mock(ExtractionBudget.class);
    private final LiveExtraction live;

    LiveExtractionTest() {
        var beans = new StaticListableBeanFactory(Map.of("provider", provider, "budget", budget));
        live = new LiveExtraction(new CatalogExtractor(new VehicleLookup(mock(VpicClient.class))),
                beans.getBeanProvider(OpenAiExtractor.class),
                beans.getBeanProvider(ExtractionBudget.class));
        when(provider.ready()).thenReturn(true);
    }

    @Test
    void checksBudgetBeforeCallingOpenAiAndSettlesAnAcceptedResponse() {
        when(budget.reserve(MONTH, NOW)).thenReturn(true);
        when(provider.extract(DESCRIPTION)).thenReturn(Optional.of(new OpenAiExtractor.Result(
                "vw gti", "VW GTI", List.of(), List.of(new CatalogExtractor.Part("exhaust", "aftermarket exhaust")),
                "", 5_000, 50)));
        var extraction = live.extract(DESCRIPTION, NOW);
        assertThat(extraction.source()).isEqualTo("live");
        assertThat(extraction.facts().modIds()).containsExactly("exhaust");
        var calls = inOrder(budget, provider);
        calls.verify(budget).reserve(MONTH, NOW);
        calls.verify(provider).extract(DESCRIPTION);
        calls.verify(budget).settle(MONTH, 1_288);
    }

    @Test
    void usesCatalogWhenBudgetCannotBeReserved() {
        when(budget.reserve(MONTH, NOW)).thenThrow(new DataAccessResourceFailureException("test"));
        var extraction = live.extract(DESCRIPTION, NOW);
        assertThat(extraction.source()).isEqualTo("catalog");
        assertThat(extraction.fallbackReason()).isEqualTo("unavailable");
        assertThat(extraction.match().status()).isEqualTo("matched");
        verify(provider).ready();
        verifyNoMoreInteractions(provider);
    }

    @Test
    void limitsAndPossibleContactDetailsAvoidAProviderCall() {
        assertThat(live.extract(DESCRIPTION, NOW).fallbackReason()).isEqualTo("limited");
        assertThat(live.extract(DESCRIPTION + " alex@example.com", NOW).fallbackReason()).isEqualTo("private_input");
        verify(budget).reserve(MONTH, NOW);
        verifyNoMoreInteractions(budget);
        verify(provider, times(2)).ready();
        verifyNoMoreInteractions(provider);
    }

    @Test
    void retainsTheReservationWhenProviderReturnsNoValidatedFacts() {
        when(budget.reserve(MONTH, NOW)).thenReturn(true);
        when(provider.extract(DESCRIPTION)).thenReturn(Optional.empty());
        assertThat(live.extract(DESCRIPTION, NOW).fallbackReason()).isEqualTo("unavailable");
        verify(budget).reserve(MONTH, NOW);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void pausesAtUtcRolloverAndWhenPricingReviewIsDue() {
        assertThat(live.extract(DESCRIPTION, OffsetDateTime.parse("2026-11-30T23:59:30Z")).fallbackReason())
                .isEqualTo("limited");
        assertThat(live.extract(DESCRIPTION, OffsetDateTime.parse("2027-02-01T00:00:00Z")).fallbackReason())
                .isEqualTo("unavailable");
        verifyNoInteractions(budget);
    }
}
