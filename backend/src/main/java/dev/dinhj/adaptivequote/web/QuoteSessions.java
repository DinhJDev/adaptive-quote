package dev.dinhj.adaptivequote.web;

import dev.dinhj.adaptivequote.quote.QuoteReceipts.ReceiptFailure;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

@Component
public class QuoteSessions {
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String origin;
    private final boolean secure;
    private final String cookieName;
    private final byte[] secret;

    QuoteSessions(@Value("${quote.public-origin}") String origin,
                  @Value("${quote.session-secret:}") String secret) {
        URI uri = URI.create(origin);
        secure = "https".equals(uri.getScheme());
        boolean local = "http".equals(uri.getScheme()) && uri.getHost() != null
                && Set.of("localhost", "127.0.0.1").contains(uri.getHost());
        if ((!secure && !local) || uri.getHost() == null || !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Set QUOTE_PUBLIC_ORIGIN to an HTTPS origin or a local HTTP origin.");
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if ((secure || key.length != 0) && key.length < 32) {
            throw new IllegalArgumentException("QUOTE_SESSION_SECRET must contain at least 32 bytes.");
        }
        this.origin = origin;
        this.cookieName = secure ? "__Host-adaptive_quote" : "adaptive_quote";
        this.secret = key.length == 0 ? randomBytes() : key;
    }

    void requireOrigin(HttpServletRequest request) {
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN))) {
            throw new ReceiptFailure(HttpStatus.FORBIDDEN, "INVALID_ORIGIN", "Open the quote from this site's address.");
        }
    }

    Optional<Owner> owner(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .findFirst()
                .flatMap(cookie -> validOwner(cookie.getValue()));
    }

    Owner create() {
        return new Owner(ENCODER.encodeToString(randomBytes()));
    }

    String digest(Owner owner) {
        return HexFormat.of().formatHex(sign("owner:" + owner.nonce()));
    }

    ResponseCookie cookie(Owner owner) {
        String payload = owner.nonce() + "." + Instant.now().plus(RETENTION).getEpochSecond();
        String value = payload + "." + ENCODER.encodeToString(sign("session:" + payload));
        return ResponseCookie.from(cookieName, value).path("/").httpOnly(true).secure(secure)
                .sameSite("Strict").maxAge(RETENTION).build();
    }

    private Optional<Owner> validOwner(String value) {
        if (value == null || value.length() > 128) return Optional.empty();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !parts[0].matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        try {
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = sign("session:" + parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(signature, expected)
                    || Long.parseLong(parts[1]) <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new Owner(parts[0]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    record Owner(String nonce) {
    }
}
