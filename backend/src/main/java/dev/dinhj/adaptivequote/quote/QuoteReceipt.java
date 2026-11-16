package dev.dinhj.adaptivequote.quote;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public record QuoteReceipt(UUID quoteId, String catalogVersion, QuoteSelection selection,
                           List<QuotePricing.Offer> lineItems, int totalMinor, String currency,
                           Instant createdAt, Instant expiresAt, CheckoutExample checkoutExample) {
    static QuoteReceipt from(UUID id, String catalogVersion, QuoteSelection selection,
                             List<QuotePricing.Offer> items, int totalMinor, String currency,
                             Instant createdAt, Instant expiresAt) {
        List<CheckoutLine> checkoutLines = items.stream()
                .map(item -> new CheckoutLine(
                        new PriceData(currency.toLowerCase(Locale.ROOT), item.amountMinor(), new ProductData(item.label())),
                        1))
                .toList();
        return new QuoteReceipt(id, catalogVersion, selection, items, totalMinor, currency, createdAt, expiresAt,
                new CheckoutExample("payment", checkoutLines, "https://example.com/quote-complete"));
    }

    public record CheckoutExample(String mode, @JsonProperty("line_items") List<CheckoutLine> lineItems,
                                  @JsonProperty("success_url") String successUrl) {
    }

    public record CheckoutLine(@JsonProperty("price_data") PriceData priceData, int quantity) {
    }

    public record PriceData(String currency, @JsonProperty("unit_amount") int unitAmount,
                            @JsonProperty("product_data") ProductData productData) {
    }

    public record ProductData(String name) {
    }
}
