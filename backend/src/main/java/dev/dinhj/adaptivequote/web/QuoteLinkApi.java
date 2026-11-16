package dev.dinhj.adaptivequote.web;

import dev.dinhj.adaptivequote.quote.QuoteLinks;
import dev.dinhj.adaptivequote.quote.QuoteReceipts.ReceiptFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/quote-links", produces = MediaType.APPLICATION_JSON_VALUE)
class QuoteLinkApi {
    private final QuoteLinks links;
    private final QuoteSessions sessions;
    private final String origin;

    QuoteLinkApi(QuoteLinks links, QuoteSessions sessions, @Value("${quote.public-origin}") String origin) {
        this.links = links;
        this.sessions = sessions;
        this.origin = origin;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareLink> create(@Valid @RequestBody QuoteLinks.CreateRequest input, HttpServletRequest request) {
        sessions.requireOrigin(request);
        sessions.owner(request).orElseThrow(() -> new ReceiptFailure(
                HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Refresh your quote before creating a link."));
        var created = links.create(input);
        return ResponseEntity.created(URI.create("/api/v1/quote-links/" + created.id()))
                .cacheControl(CacheControl.noStore())
                .body(new ShareLink(origin + "/?quote=" + created.id(), created.expiresAt()));
    }

    @GetMapping("/{id}")
    ResponseEntity<QuoteLinks.Snapshot> open(@PathVariable UUID id) {
        var snapshot = links.open(id).orElseThrow(() -> new ReceiptFailure(
                HttpStatus.NOT_FOUND, "QUOTE_LINK_NOT_FOUND", "This quote link has expired or is unavailable."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
    }

    record ShareLink(String url, OffsetDateTime expiresAt) {}
}
