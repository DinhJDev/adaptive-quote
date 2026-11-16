package dev.dinhj.adaptivequote.quote;

import dev.dinhj.adaptivequote.catalog.Catalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuoteReceipts {
    private final QuoteReceiptRepository repository;
    private final QuotePricing pricing;
    public QuoteReceipts(QuoteReceiptRepository repository, QuotePricing pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SaveResult save(UUID id, String owner, QuoteSaveRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        QuoteSelection selection = request.selection().canonical();
        String fingerprint = fingerprint(request.catalogVersion(), selection);
        Optional<QuoteReceiptRepository.SavedQuote> prior = repository.find(id, owner, now);
        if (prior.isPresent()) return replay(prior.get(), fingerprint);

        if (!Catalog.VERSION.equals(request.catalogVersion())) {
            throw new ReceiptFailure(HttpStatus.CONFLICT, "CATALOG_CHANGED", "Prices have changed. Review them again before saving.");
        }
        if (selection.serviceId() == null) throw new QuotePricing.InvalidSelection("Choose a service before saving.");

        QuotePricing.Preview preview = pricing.preview(selection);
        if (preview.customReviewReason() != null) {
            throw new QuotePricing.InvalidSelection("This build needs a tuner review before a quote can be saved.");
        }
        QuoteReceipt receipt = QuoteReceipt.from(id, preview.catalogVersion(), selection, preview.lineItems(),
                preview.totalMinor(), preview.currency(), now, now.plus(Duration.ofDays(7)));

        if (repository.insert(owner, fingerprint, receipt)) return new SaveResult(receipt, true);

        return replay(repository.find(id, owner, now).orElseThrow(QuoteReceipts::missing), fingerprint);
    }

    public QuoteReceipt open(UUID id, String owner) {
        return repository.find(id, owner, Instant.now())
                .map(QuoteReceiptRepository.SavedQuote::receipt)
                .orElseThrow(QuoteReceipts::missing);
    }

    public static ReceiptFailure missing() {
        return new ReceiptFailure(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "This saved quote is unavailable in this browser.");
    }

    private SaveResult replay(QuoteReceiptRepository.SavedQuote prior, String fingerprint) {
        if (!prior.fingerprint().equals(fingerprint)) {
            throw new ReceiptFailure(HttpStatus.CONFLICT, "QUOTE_CONFLICT", "This quote was already saved with different selections.");
        }
        return new SaveResult(prior.receipt(), false);
    }

    private String fingerprint(String version, QuoteSelection selection) {
        String canonical = String.join("\n", version, selection.vehicleId(), selection.modStatus(),
                String.join(",", selection.modIds()), selection.serviceId() == null ? "" : selection.serviceId(),
                String.join(",", selection.addOnIds()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record SaveResult(QuoteReceipt receipt, boolean created) {
    }

    public static final class ReceiptFailure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public ReceiptFailure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
