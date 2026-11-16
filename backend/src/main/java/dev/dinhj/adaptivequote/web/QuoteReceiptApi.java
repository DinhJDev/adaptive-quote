package dev.dinhj.adaptivequote.web;

import dev.dinhj.adaptivequote.quote.QuoteReceipt;
import dev.dinhj.adaptivequote.quote.QuoteReceipts;
import dev.dinhj.adaptivequote.quote.QuoteReceipts.ReceiptFailure;
import dev.dinhj.adaptivequote.quote.QuoteSaveRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
class QuoteReceiptApi {
    private final QuoteReceipts receipts;
    private final QuoteSessions sessions;

    QuoteReceiptApi(QuoteReceipts receipts, QuoteSessions sessions) {
        this.receipts = receipts;
        this.sessions = sessions;
    }

    @PutMapping(path = "/{quoteId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<QuoteReceipt> save(@PathVariable UUID quoteId, @Valid @RequestBody QuoteSaveRequest input,
                                      HttpServletRequest request) {
        sessions.requireOrigin(request);
        QuoteSessions.Owner owner = sessions.owner(request).orElseThrow(() -> new ReceiptFailure(
                HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "This browser's quote session expired."));
        QuoteReceipts.SaveResult result = receipts.save(quoteId, sessions.digest(owner), input);
        var response = result.created()
                ? ResponseEntity.created(URI.create("/api/v1/quotes/" + quoteId))
                : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, sessions.cookie(owner).toString())
                .body(result.receipt());
    }

    @GetMapping("/{quoteId}")
    ResponseEntity<QuoteReceipt> open(@PathVariable UUID quoteId, HttpServletRequest request) {
        QuoteSessions.Owner owner = sessions.owner(request).orElseThrow(QuoteReceipts::missing);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(receipts.open(quoteId, sessions.digest(owner)));
    }
}
