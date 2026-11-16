package dev.dinhj.adaptivequote.quote;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class QuoteReceiptRepository {
    private static final TypeReference<List<QuotePricing.Offer>> LINE_ITEMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    QuoteReceiptRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean insert(String owner, String fingerprint, QuoteReceipt receipt) {
        return jdbc.sql("""
                INSERT INTO quotes (id, owner_digest, request_digest, catalog_version, record_version,
                                    selection, line_items, total_minor, currency, created_at, expires_at)
                VALUES (:id, :owner, :fingerprint, :catalog, 1, CAST(:selection AS jsonb), CAST(:items AS jsonb),
                        :total, :currency, :created, :expires)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", receipt.quoteId())
                .param("owner", owner)
                .param("fingerprint", fingerprint)
                .param("catalog", receipt.catalogVersion())
                .param("selection", json.writeValueAsString(receipt.selection()))
                .param("items", json.writeValueAsString(receipt.lineItems()))
                .param("total", receipt.totalMinor())
                .param("currency", receipt.currency())
                .param("created", receipt.createdAt().atOffset(ZoneOffset.UTC))
                .param("expires", receipt.expiresAt().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    Optional<SavedQuote> find(UUID id, String owner, Instant now) {
        return jdbc.sql("""
                SELECT id, request_digest, catalog_version, selection, line_items, total_minor, currency,
                       created_at, expires_at
                FROM quotes
                WHERE id = :id AND owner_digest = :owner AND record_version = 1 AND expires_at > :now
                """)
                .param("id", id)
                .param("owner", owner)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((result, row) -> new SavedQuote(result.getString("request_digest"),
                        QuoteReceipt.from(result.getObject("id", UUID.class), result.getString("catalog_version"),
                                json.readValue(result.getString("selection"), QuoteSelection.class),
                                json.readValue(result.getString("line_items"), LINE_ITEMS),
                                result.getInt("total_minor"), result.getString("currency"),
                                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                                result.getObject("expires_at", OffsetDateTime.class).toInstant())))
                .optional();
    }

    record SavedQuote(String fingerprint, QuoteReceipt receipt) {
    }
}
