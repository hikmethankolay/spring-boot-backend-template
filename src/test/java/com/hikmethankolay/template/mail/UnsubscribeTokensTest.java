package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.hikmethankolay.template.TestProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests of {@link UnsubscribeTokens}. */
class UnsubscribeTokensTest {

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(TestProperties.create());

    @Test
    void issuedTokenVerifies() {
        String token = tokens.issue(42, MailCategory.PRODUCT_UPDATES);

        assertThat(token).startsWith("v1.").matches("[A-Za-z0-9_.-]+");
        assertThat(tokens.verify(token)).contains(new UnsubscribeTokens.Claim(42, MailCategory.PRODUCT_UPDATES));
    }

    @Test
    void anotherUsersIdWithTheSameSignatureFails() {
        String[] parts = tokens.issue(42, MailCategory.PRODUCT_UPDATES).split("\\.");
        String forged = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("43.product-updates".getBytes(StandardCharsets.UTF_8));

        assertThat(tokens.verify(parts[0] + "." + forged + "." + parts[2])).isEmpty();
    }

    @Test
    void alteredSignatureFails() {
        String token = tokens.issue(42, MailCategory.PRODUCT_UPDATES);
        String altered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        assertThat(tokens.verify(altered)).isEmpty();
    }

    @Test
    void linksSignedBeforeAKeyRotationStillWork() {
        String old = tokens.issue(42, MailCategory.PRODUCT_UPDATES);
        String newKey = "new-mfa-key-that-is-at-least-32-characters";
        UnsubscribeTokens rotated = new UnsubscribeTokens(TestProperties.withMfaKeys(newKey, TestProperties.MFA_KEY));

        assertThat(rotated.verify(old)).contains(new UnsubscribeTokens.Claim(42, MailCategory.PRODUCT_UPDATES));
        // New links are signed with the first key: they do not verify under the old one alone.
        String fresh = rotated.issue(42, MailCategory.PRODUCT_UPDATES);
        assertThat(tokens.verify(fresh)).isEmpty();
        assertThat(new UnsubscribeTokens(TestProperties.withMfaKeys(newKey)).verify(fresh))
                .isPresent();
        // Dropping the old key breaks the links it signed.
        assertThat(new UnsubscribeTokens(TestProperties.withMfaKeys(newKey)).verify(old))
                .isEmpty();
    }

    @Test
    void transactionalCategoryCannotBeTurnedOff() {
        assertThat(tokens.verify(tokens.issue(42, MailCategory.ACCOUNT))).isEmpty();
        assertThat(tokens.verify(tokens.issue(42, MailCategory.SECURITY))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"v1", "v1.a", "v1..", "v2.MTIzLnByb2R1Y3QtdXBkYXRlcw.c2ln", "v1.!!!.!!!", "v1.a.b.c"})
    void malformedTokensFail(String token) {
        assertThat(tokens.verify(token)).isEmpty();
    }
}
