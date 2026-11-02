package com.hikmethankolay.template.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Signatures must match the Standard Webhooks reference libraries byte for byte. */
class WebhookSignerTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    private static final String MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";

    private static final long TIMESTAMP = 1614265330L;

    private static final String BODY = "{\"test\": 2432232314}";

    @Test
    void signsLikeTheReferenceImplementation() {
        // Test vector of the Standard Webhooks libraries.
        assertThat(WebhookSigner.signatureHeader(List.of(SECRET), MESSAGE_ID, TIMESTAMP, BODY))
                .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void duringARotationEverySecretSigns() {
        String other = WebhookSigner.newSecret();

        String header = WebhookSigner.signatureHeader(List.of(other, SECRET), MESSAGE_ID, TIMESTAMP, BODY);

        assertThat(header.split(" "))
                .containsExactly(
                        "v1," + WebhookSigner.sign(other, MESSAGE_ID, TIMESTAMP, BODY),
                        "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void newSecretsAreRandom32ByteKeys() {
        String secret = WebhookSigner.newSecret();

        assertThat(secret).startsWith("whsec_").isNotEqualTo(WebhookSigner.newSecret());
        assertThat(Base64.getDecoder().decode(secret.substring("whsec_".length())))
                .hasSize(32);
    }

    @Test
    void retriesWaitLongerEachTimeUpToADay() {
        assertThat(WebhookDispatcher.backoff(0)).hasSeconds(30);
        assertThat(WebhookDispatcher.backoff(1)).hasMinutes(2);
        assertThat(WebhookDispatcher.backoff(4)).hasHours(6);
        assertThat(WebhookDispatcher.backoff(5)).hasDays(1);
        assertThat(WebhookDispatcher.backoff(20)).hasDays(1);
    }
}
