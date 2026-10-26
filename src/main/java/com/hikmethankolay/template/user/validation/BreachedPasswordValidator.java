package com.hikmethankolay.template.user.validation;

import com.hikmethankolay.template.config.AppProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Rejects common and breached passwords, the blocklist NIST SP 800-63B-4 requires for new passwords.
 *
 * <p>The bundled {@code breached-passwords.txt} holds the entries of 8 or more characters from the
 * NCSC top 100,000 list (SecLists, MIT licence), in lower case; passwords are compared in lower case
 * too. With {@code app.account.pwned-check-enabled} the password is also looked up in the Have I Been
 * Pwned range API: only the first 5 hex digits of its SHA-1 are sent (k-anonymity).</p>
 */
@Component
public class BreachedPasswordValidator {

    private static final Logger log = LoggerFactory.getLogger(BreachedPasswordValidator.class);

    /** Classpath location of the bundled list. */
    static final String LIST = "/breached-passwords.txt";

    private static final String RANGE_API = "https://api.pwnedpasswords.com/range/";

    private final Set<String> breached;

    private final RestClient pwned;

    /**
     * Loads the bundled list and, when enabled, prepares the Have I Been Pwned client with 2-second
     * connect and 3-second read timeouts.
     *
     * @param properties {@code app.account.pwned-check-enabled}
     */
    public BreachedPasswordValidator(AppProperties properties) {
        this.breached = load();
        if (properties.account().pwnedCheckEnabled()) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(2));
            requestFactory.setReadTimeout(Duration.ofSeconds(3));
            this.pwned = RestClient.builder().requestFactory(requestFactory).build();
        } else {
            this.pwned = null;
        }
    }

    /**
     * Tells whether a password is common or known to be breached.
     *
     * @param password the new password
     * @return {@code true} if it is on the bundled list or, when enabled, in Have I Been Pwned
     */
    public boolean isBreached(String password) {
        return breached.contains(password.toLowerCase(Locale.ROOT)) || (pwned != null && isPwned(password));
    }

    /**
     * Looks the password up in Have I Been Pwned. An unreachable API counts as "not breached", so an
     * outage does not block sign-ups; the bundled list still applies.
     *
     * @param password the new password
     * @return {@code true} if the API lists the password's hash with a non-zero count
     */
    private boolean isPwned(String password) {
        String hash = HexFormat.of().withUpperCase().formatHex(sha1(password));
        String suffix = hash.substring(5) + ":";
        try {
            String body = range(hash.substring(0, 5));
            // Add-Padding mixes in fake entries with a count of 0.
            return body != null && body.lines().anyMatch(line -> line.startsWith(suffix) && !line.endsWith(":0"));
        } catch (RestClientException e) {
            // The type only: the message could echo response content into the log.
            log.warn(
                    "Have I Been Pwned is unreachable ({}); checked the bundled list only",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Fetches the hash suffixes for a prefix from the range API.
     *
     * @param prefix first 5 hex digits of the SHA-1, upper case
     * @return the response body, one {@code SUFFIX:COUNT} per line
     */
    String range(String prefix) {
        return pwned.get()
                .uri(RANGE_API + prefix)
                .header("Add-Padding", "true")
                .retrieve()
                .body(String.class);
    }

    /**
     * Hashes a password with SHA-1, the hash the range API is keyed by.
     *
     * @param password the password
     * @return the 20-byte digest
     */
    private static byte[] sha1(String password) {
        try {
            // Not used for storage: SHA-1 is the lookup key of the range API.
            return MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every JRE", e);
        }
    }

    /**
     * Reads the bundled list.
     *
     * @return the lower-case entries
     */
    private static Set<String> load() {
        try (InputStream in = BreachedPasswordValidator.class.getResourceAsStream(LIST)) {
            if (in == null) {
                throw new IllegalStateException(LIST + " is missing from the classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().filter(line -> !line.isEmpty()).collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
