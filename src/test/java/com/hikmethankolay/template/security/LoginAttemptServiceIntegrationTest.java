package com.hikmethankolay.template.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hikmethankolay.template.IntegrationTest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Login lockout counters in Redis (3 attempts per IP and 5 per account in the test profile). */
class LoginAttemptServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private LoginAttemptService service;

    @Autowired
    private StringRedisTemplate redis;

    private static String uniqueKey(String type) {
        return type + ":" + UUID.randomUUID();
    }

    @Test
    void aPasswordConfirmationThatCannotBeCheckedIsNotAFailedAttempt() {
        long userId = Math.abs(UUID.randomUUID().getMostSignificantBits());

        // More failures than the IP (3) and the account (5) allow, had they counted.
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> service.checkReentered(userId, () -> {
                        throw new IllegalStateException("database down");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(service.checkReentered(userId, () -> true)).isTrue();
    }

    @Test
    void blocksIpAfterMaxAttempts() {
        String ip = uniqueKey("ip");
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryAcquire(ip, uniqueKey("account"))).isTrue();
        }

        assertThat(service.tryAcquire(ip, uniqueKey("account"))).isFalse();
    }

    @Test
    void blocksAccountAfterAccountMaxAttempts() {
        String account = uniqueKey("account");
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryAcquire(uniqueKey("ip"), account)).isTrue();
        }

        assertThat(service.tryAcquire(uniqueKey("ip"), account)).isFalse();
    }

    @Test
    void aBlockedAccountDoesNotCountAgainstTheIp() {
        String account = uniqueKey("account");
        for (int i = 0; i < 5; i++) {
            service.tryAcquire(uniqueKey("ip"), account);
        }
        String ip = uniqueKey("ip");
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryAcquire(ip, account)).isFalse();
        }

        assertThat(service.tryAcquire(ip, uniqueKey("account"))).isTrue();
    }

    @Test
    void oneIpCannotLockAnAccount() {
        String ip = uniqueKey("ip");
        String account = uniqueKey("account");
        for (int i = 0; i < 3; i++) {
            service.tryAcquire(ip, account);
        }

        assertThat(service.tryAcquire(ip, account)).isFalse();
        assertThat(service.tryAcquire(uniqueKey("ip"), account)).isTrue();
    }

    @Test
    void resetUnblocksAccount() {
        String account = uniqueKey("account");
        for (int i = 0; i < 5; i++) {
            service.tryAcquire(uniqueKey("ip"), account);
        }

        service.reset(account);

        assertThat(service.tryAcquire(uniqueKey("ip"), account)).isTrue();
    }

    @Test
    void releaseTakesBackOneAttempt() {
        String ip = uniqueKey("ip");
        for (int i = 0; i < 3; i++) {
            service.tryAcquire(ip, uniqueKey("account"));
        }

        service.release(ip);

        assertThat(service.tryAcquire(ip, uniqueKey("account"))).isTrue();
        assertThat(service.tryAcquire(ip, uniqueKey("account"))).isFalse();
    }

    @Test
    void concurrentAttemptsCannotExceedTheLimit() throws Exception {
        String ip = uniqueKey("ip");
        String account = uniqueKey("account");
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<Boolean>> results =
                    pool.invokeAll(Collections.nCopies(20, (Callable<Boolean>) () -> service.tryAcquire(ip, account)));
            long allowed = 0;
            for (Future<Boolean> result : results) {
                allowed += result.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(3);
        }
    }

    @Test
    void countersExpireAfterLockDuration() {
        String ip = uniqueKey("ip");
        service.tryAcquire(ip, uniqueKey("account"));

        Long ttlSeconds = redis.getExpire("spring-boot-template:login-failures:" + ip);
        assertThat(ttlSeconds)
                .isPositive()
                .isLessThanOrEqualTo(service.lockDuration().toSeconds());
    }
}
