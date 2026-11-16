package dev.dinhj.adaptivequote.extraction;

import com.openai.errors.OpenAIException;
import dev.dinhj.adaptivequote.extraction.CatalogExtractor.Extraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class LiveExtraction {
    private static final Logger LOG = LoggerFactory.getLogger(LiveExtraction.class);
    private static final LocalDate RATES_REVIEW_BY = LocalDate.of(2027, 2, 1);
    private static final Pattern CONTACT = Pattern.compile("@|https?://|(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}",
            Pattern.CASE_INSENSITIVE);
    private final CatalogExtractor catalog;
    private final OpenAiExtractor openAi;
    private final ObjectProvider<ExtractionBudget> budgets;

    LiveExtraction(CatalogExtractor catalog, ObjectProvider<OpenAiExtractor> openAi,
                   ObjectProvider<ExtractionBudget> budgets) {
        this.catalog = catalog;
        this.openAi = openAi.getIfAvailable();
        this.budgets = budgets;
    }

    public boolean enabled() {
        return available(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public Extraction extract(String description) {
        return extract(description, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public Extraction extractModifications(String description) {
        return extract(description, OffsetDateTime.now(ZoneOffset.UTC), true);
    }

    Extraction extract(String description, OffsetDateTime start) {
        return extract(description, start, false);
    }

    private Extraction extract(String description, OffsetDateTime start, boolean modificationsOnly) {
        if (!available(start)) return fallback(description, "unavailable", modificationsOnly);
        if (containsContactDetails(description)) return fallback(description, "private_input", modificationsOnly);

        var month = start.toLocalDate().withDayOfMonth(1);
        if (Duration.between(start, month.plusMonths(1).atStartOfDay().atOffset(ZoneOffset.UTC)).toSeconds() < 60) {
            return fallback(description, "limited", modificationsOnly);
        }
        var budget = budgets.getObject();
        try {
            if (!budget.reserve(month, start)) return fallback(description, "limited", modificationsOnly);
        } catch (DataAccessException ex) {
            LOG.warn("AI extraction budget unavailable");
            return fallback(description, "unavailable", modificationsOnly);
        }

        long startedNanos = System.nanoTime();
        try {
            var result = openAi.extract(description);
            if (result.isEmpty()) return fallback(description, "unavailable", modificationsOnly);
            var facts = result.get();
            try {
                budget.settle(month, accountedMicros(facts.inputTokens(), facts.outputTokens()));
            } catch (DataAccessException ex) {
                LOG.warn("AI extraction reservation retained after settlement error");
            }
            LOG.info("AI extraction completed in {} ms ({} input, {} output tokens)",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                    facts.inputTokens(), facts.outputTokens());
            return modificationsOnly ? catalog.resolveLiveModifications(description, facts) : catalog.resolveLive(description, facts);
        } catch (OpenAIException | JacksonException | IllegalArgumentException | NoSuchElementException ex) {
            LOG.warn("AI extraction unavailable ({})", ex.getClass().getSimpleName());
            return fallback(description, "unavailable", modificationsOnly);
        }
    }

    private boolean available(OffsetDateTime time) {
        return openAi != null && openAi.ready() && time.toLocalDate().isBefore(RATES_REVIEW_BY);
    }

    public static boolean containsContactDetails(String text) {
        return CONTACT.matcher(text).find();
    }

    private Extraction fallback(String description, String reason, boolean modificationsOnly) {
        var extraction = modificationsOnly ? catalog.extractModifications(description) : catalog.extractDescription(description);
        return extraction.withFallback(reason);
    }

    private static int accountedMicros(long input, long output) {
        // Standard long-context rates in USD per million tokens: $0.25 input/cache write, $0.75 output.
        return (int) Math.max(1_000, (input + 3 * output + 3) / 4);
    }
}
