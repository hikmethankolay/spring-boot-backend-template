package com.hikmethankolay.template.auth;

import com.hikmethankolay.template.common.ClientIp;
import com.hikmethankolay.template.common.SecureTokens;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.mail.SecurityMailer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.WebUtils;

/**
 * Recognizes the devices each user logs in from ({@code known_devices}), so a login from a new one is reported by
 * email.
 *
 * <p>A browser is recognized by a device cookie, as in OWASP's "device cookies": a random token set at every login,
 * HttpOnly and sent only to {@code /api/v1/auth}, whose SHA-256 is stored here. A client that keeps no cookies, such
 * as a mobile app using Bearer tokens, is recognized when the same device (browser family and operating system)
 * logged in from the same network (IPv4 /24, IPv6 /64) within the lookback. No client-side fingerprint is collected:
 * the user agent and address are what every session already records. Devices unused for the lookback are
 * forgotten, except each user's latest, and the first device of an account is never reported. The network fallback
 * is used for public addresses only: a private one may be a proxy's that every client shares when
 * {@code FORWARD_HEADERS_STRATEGY} is not set.</p>
 */
@Component
public class KnownDevices {

    /** What a user agent naming no known browser or operating system is shown as. */
    static final String UNKNOWN_DEVICE = "Unknown device";

    private static final Logger log = LoggerFactory.getLogger(KnownDevices.class);

    // Checked in order: Edge, Opera and Samsung Internet also claim Chrome, and Chrome claims Safari.
    private static final List<Rule> BROWSERS = List.of(
            new Rule("Edg(e|A|iOS)?/", "Edge"),
            new Rule("OPR/|Opera", "Opera"),
            new Rule("SamsungBrowser/", "Samsung Internet"),
            new Rule("Firefox/|FxiOS/", "Firefox"),
            new Rule("Chrome/|CriOS/", "Chrome"),
            new Rule("Safari/", "Safari"));

    // iOS claims "like Mac OS X", Android and ChromeOS claim Linux.
    private static final List<Rule> SYSTEMS = List.of(
            new Rule("Windows", "Windows"),
            new Rule("iPhone|iPad|iPod", "iOS"),
            new Rule("Mac OS X|Macintosh", "macOS"),
            new Rule("Android", "Android"),
            new Rule("CrOS", "ChromeOS"),
            new Rule("Linux", "Linux"));

    private final JdbcTemplate jdbc;

    private final SecurityMailer mailer;

    private final String cookieName;

    private final long lookbackSeconds;

    /**
     * Creates the registry.
     *
     * @param jdbc       database access
     * @param mailer     sends the new-login alert
     * @param properties application properties holding the cookie settings
     * @param lookback   how long an unused device is remembered
     */
    public KnownDevices(
            JdbcTemplate jdbc,
            SecurityMailer mailer,
            AppProperties properties,
            @Value("${app.mail.security-alerts.new-login-lookback}") Duration lookback) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.cookieName = properties.cookie().deviceCookieName();
        this.lookbackSeconds = lookback.toSeconds();
    }

    /**
     * Records a login from the current request and, if it comes from a device the user has not used within the
     * lookback, queues the new-login alert in the same transaction.
     *
     * @param userId the user who logged in
     * @return the device token to set as the device cookie: the one the browser sent if it is known, a new one for a
     *     new device, or {@code null} for a client recognized without the cookie
     */
    @Transactional
    public String recordLogin(long userId) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest()
                : null;
        String ip = request == null ? null : request.getRemoteAddr();
        String device = describe(request == null ? null : request.getHeader(HttpHeaders.USER_AGENT));
        // A private address may be a proxy's, shared by every client: then only the cookie recognizes a device.
        String network = ClientIp.isPublic(ip) ? ClientIp.network(ip) : null;
        Cookie cookie = request == null ? null : WebUtils.getCookie(request, cookieName);
        // A known cookie only refreshes its row: the device and network stay those of the first login, which a
        // cookieless client on the same device and network is matched against.
        if (cookie != null
                && jdbc.update(
                                "UPDATE known_devices SET last_seen_at = now() WHERE user_id = ? AND token_hash = ?",
                                userId,
                                SecureTokens.hash(cookie.getValue()))
                        == 1) {
            return cookie.getValue();
        }
        // A client without the cookie, such as a mobile app, on a device and network seen within the lookback:
        // refresh that row rather than add one per login, and set no cookie it would not keep anyway.
        if (network != null
                && jdbc.update(
                                "UPDATE known_devices SET last_seen_at = now() WHERE user_id = ? AND device = ? "
                                        + "AND network = ? "
                                        + "AND last_seen_at > now() - ? * interval '1 second'",
                                userId,
                                device,
                                network,
                                lookbackSeconds)
                        > 0) {
            return null;
        }
        boolean loggedInBefore = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM known_devices WHERE user_id = ?)", Boolean.class, userId));
        String token = SecureTokens.generate();
        jdbc.update(
                "INSERT INTO known_devices (user_id, token_hash, device, network, created_at, last_seen_at) "
                        + "VALUES (?, ?, ?, ?, now(), now())",
                userId,
                SecureTokens.hash(token),
                device,
                network == null ? "unknown" : network);
        if (loggedInBefore) {
            mailer.newLogin(
                    jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId), device, ip);
        }
        return token;
    }

    /**
     * Forgets the devices unused for the lookback, once an hour, except each user's latest: an account idle for
     * longer than the lookback still has its next sign-in from a new device reported. Safe to run on several
     * instances at once.
     */
    @Scheduled(cron = "0 45 * * * *")
    @Transactional
    public void forgetUnusedDevices() {
        int deleted = jdbc.update("""
                DELETE FROM known_devices k WHERE k.last_seen_at < now() - ? * interval '1 second'
                AND EXISTS (SELECT 1 FROM known_devices n WHERE n.user_id = k.user_id AND n.last_seen_at > k.last_seen_at)
                """, lookbackSeconds);
        if (deleted > 0) {
            log.info(
                    "Forgot {} devices not used for {} days",
                    deleted,
                    Duration.ofSeconds(lookbackSeconds).toDays());
        }
    }

    /**
     * Names a device after its browser family and operating system, as users know it.
     *
     * @param userAgent the {@code User-Agent} header, or {@code null}
     * @return e.g. {@code Chrome on Windows}, {@code Firefox}, {@code Android}, or {@link #UNKNOWN_DEVICE}
     */
    static String describe(String userAgent) {
        String browser = match(BROWSERS, userAgent);
        String system = match(SYSTEMS, userAgent);
        if (browser == null) {
            return system == null ? UNKNOWN_DEVICE : system;
        }
        return system == null ? browser : browser + " on " + system;
    }

    private static String match(List<Rule> rules, String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return rules.stream()
                .filter(rule -> rule.pattern().matcher(userAgent).find())
                .map(Rule::name)
                .findFirst()
                .orElse(null);
    }

    private record Rule(Pattern pattern, String name) {

        Rule(String regex, String name) {
            this(Pattern.compile(regex), name);
        }
    }
}
