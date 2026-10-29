package com.hikmethankolay.template.security;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.config.AppProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Brute-force protection for the login endpoint.
 *
 * <p>Login attempts are counted per client IP and per account in Redis, so the limits hold across
 * all application instances. Each attempt is counted before its password is checked, and a
 * successful one is taken back, so concurrent requests cannot exceed the limit. An IP is blocked
 * after {@code app.login.max-attempts} attempts, an account after the higher
 * {@code app.login.account-max-attempts}, so a single client cannot lock a real user out. A key is
 * unblocked after {@code app.login.lock-duration} without new failures.</p>
 *
 * <p>If Redis is unavailable, logins are refused with {@code 503} (fail closed) rather than allowed
 * without a limit; existing sessions keep working (ADR 2).</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /**
     * Atomically checks every key against its limit ({@code KEYS[i]} against {@code ARGV[i + 1]}) and,
     * if none is blocked, counts one attempt on each and (re)sets its expiry. Returns 1 if the attempt
     * may go ahead, 0 if a key is blocked (nothing is counted then). Counting before the password
     * check means concurrent requests cannot all pass a stale check.
     */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            for i, key in ipairs(KEYS) do
              if tonumber(redis.call('GET', key) or '0') >= tonumber(ARGV[i + 1]) then return 0 end
            end
            for _, key in ipairs(KEYS) do
              redis.call('INCR', key)
              redis.call('PEXPIRE', key, ARGV[1])
            end
            return 1""", Long.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if tonumber(redis.call('GET', KEYS[1]) or '0') > 0 then return redis.call('DECR', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;

    private final String keyPrefix;

    private final int maxAttempts;

    private final int accountMaxAttempts;

    private final Duration lockDuration;

    /**
     * Creates the service.
     *
     * @param redis           Redis access
     * @param properties      application properties holding the limits
     * @param applicationName value of {@code spring.application.name}, used to namespace keys
     */
    public LoginAttemptService(
            StringRedisTemplate redis,
            AppProperties properties,
            @Value("${spring.application.name}") String applicationName) {
        this.redis = redis;
        this.keyPrefix = applicationName + ":login-failures:";
        this.maxAttempts = properties.login().maxAttempts();
        this.accountMaxAttempts = properties.login().accountMaxAttempts();
        this.lockDuration = properties.login().lockDuration();
    }

    /**
     * Counts a login attempt against the IP and the account, unless one of them is already blocked.
     * Call it before checking the password; a failed check leaves the attempt counted.
     *
     * @param ipKey      the key of the client IP, limited to {@code app.login.max-attempts}
     * @param accountKey the key of the account, limited to {@code app.login.account-max-attempts}
     * @return {@code true} if the attempt may go ahead, {@code false} if a key is blocked
     * @throws ApiException if Redis is unavailable (mapped to 503)
     */
    public boolean tryAcquire(String ipKey, String accountKey) {
        try {
            Long allowed = redis.execute(
                    ACQUIRE,
                    List.of(keyPrefix + ipKey, keyPrefix + accountKey),
                    Long.toString(lockDuration.toMillis()),
                    Integer.toString(maxAttempts),
                    Integer.toString(accountMaxAttempts));
            return allowed == null || allowed == 1;
        } catch (DataAccessException ex) {
            // The keys are not logged: an unknown account's key holds the raw login identifier.
            log.warn("Could not check login failures, refusing the login: {}", ex.getMessage());
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemType.LOGIN_UNAVAILABLE,
                    "Login is temporarily unavailable. Please try again later.");
        }
    }

    /**
     * Takes back an attempt counted by {@link #tryAcquire}, e.g. because the password was right.
     *
     * @param key the key to release
     */
    public void release(String key) {
        try {
            redis.execute(RELEASE, List.of(keyPrefix + key));
        } catch (DataAccessException ex) {
            log.warn("Could not release a login attempt: {}", ex.getMessage());
        }
    }

    /**
     * Takes back an attempt whose check ended in a server error (e.g. no free database connection) instead of a
     * wrong password or code. Otherwise every error during an outage would count as a failed login, and an IP
     * shared by many users (an office, a mobile carrier) would stay locked out long after the outage.
     *
     * @param ipKey      the IP key given to {@link #tryAcquire}
     * @param accountKey the account key given to {@link #tryAcquire}
     */
    public void giveBack(String ipKey, String accountKey) {
        release(ipKey);
        release(accountKey);
    }

    /**
     * Clears the failures of a key, e.g. after a successful login.
     *
     * @param key the key to reset
     */
    public void reset(String key) {
        try {
            redis.delete(keyPrefix + key);
        } catch (DataAccessException ex) {
            log.warn("Could not reset login failures: {}", ex.getMessage());
        }
    }

    /**
     * Returns the full lock duration. It is sent as {@code Retry-After}, so it is an upper bound
     * on the remaining wait (the lock may expire sooner if the last failure was a while ago).
     *
     * @return the configured lock duration
     */
    public Duration lockDuration() {
        return lockDuration;
    }
}
