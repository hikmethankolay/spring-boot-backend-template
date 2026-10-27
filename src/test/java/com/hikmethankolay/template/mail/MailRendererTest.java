package com.hikmethankolay.template.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.thymeleaf.exceptions.TemplateInputException;

/**
 * Unit tests of {@link MailRenderer} and the templates in {@code src/main/resources/mail/}.
 *
 * <p>Each run writes the HTML of every template to {@code target/mail-preview/} for a look in a browser, and
 * compares it with {@code src/test/resources/mail-snapshots/}. After an intended change, copy the preview over the
 * snapshot.</p>
 */
class MailRendererTest {

    private static final String LINK = "https://app.example.com/verify-email?token=abc";

    private final MailRenderer renderer = new MailRenderer("Acme", "https://app.example.com");

    private RenderedMail render(String template) {
        return renderer.render(template, "Subject of " + template, Map.of("link", LINK, "ttl", "24 hours"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"verify-email", "password-reset", "sign-up-link", "already-registered"})
    void everyTemplateRendersBothBodies(String template) {
        RenderedMail mail = render(template);

        assertThat(mail.subject()).isEqualTo("Subject of " + template);
        assertThat(mail.html())
                .startsWith("<!DOCTYPE html>")
                .contains("<title>Subject of " + template + "</title>", "Acme")
                .doesNotContain(" th:", "xmlns:th", "<style");
        assertThat(mail.text()).contains("Acme").doesNotContain("[(");
        assertThat(mail.text().lines()).allSatisfy(line -> assertThat(line).hasSizeLessThan(78));
    }

    @ParameterizedTest
    @ValueSource(strings = {"verify-email", "password-reset", "sign-up-link"})
    void linkIsInBothBodiesAndExpiryIsStated(String template) {
        RenderedMail mail = render(template);

        assertThat(mail.html()).contains("href=\"" + LINK + "\"", "24 hours");
        assertThat(mail.text()).contains(LINK, "24 hours");
    }

    @Test
    void valuesAreEscapedInHtml() {
        RenderedMail mail = renderer.render(
                "verify-email", "<b>Hi</b>", Map.of("link", "https://x.test/?a=<script>", "ttl", "<i>1</i>"));

        assertThat(mail.html())
                .doesNotContain("<script>", "<b>Hi", "<i>1")
                .contains("&lt;script&gt;", "&lt;b&gt;Hi", "&lt;i&gt;1");
    }

    @Test
    void htmlTemplatesNeverPrintUnescapedValues() throws IOException {
        List<Path> templates;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/mail"))) {
            templates = files.filter(file -> file.toString().endsWith(".html")).toList();
        }

        assertThat(templates).isNotEmpty().allSatisfy(file -> {
            // Parser-level comments are dropped from the output and may name the forbidden forms.
            String markup = Files.readString(file).replaceAll("(?s)<!--/\\*.*?\\*/-->", "");
            assertThat(markup).as(file.toString()).doesNotContain("th:utext", "[(");
        });
    }

    @Test
    void optionalEmailGetsTheUnsubscribeFooter() {
        RenderedMail mail = renderer.render(
                "broadcast/announcement",
                "News",
                Map.of("title", "What's new", "paragraphs", List.of("One", "Two")),
                MailCategory.PRODUCT_UPDATES,
                "zz9");

        assertThat(mail.html())
                .contains(
                        "href=\"https://app.example.com/unsubscribe?token=zz9\"",
                        "Product updates",
                        "href=\"https://app.example.com/settings/emails\"",
                        "<p>One</p>");
        assertThat(mail.text())
                .contains(
                        "Unsubscribe from Product updates: https://app.example.com/unsubscribe?token=zz9",
                        "Manage email preferences: https://app.example.com/settings/emails");
    }

    @Test
    void transactionalEmailNeverGetsTheFooter() {
        Map<String, Object> model = Map.of("link", LINK, "ttl", "1 hour", "unsubscribeUrl", "https://evil.test");

        RenderedMail plain = renderer.render("verify-email", "Subject", model);
        RenderedMail security = renderer.render("verify-email", "Subject", model, MailCategory.SECURITY, "zz9");

        for (RenderedMail mail : List.of(plain, security)) {
            assertThat(mail.html()).doesNotContain("evil.test", "Unsubscribe", "zz9");
            assertThat(mail.text()).doesNotContain("Unsubscribe", "zz9");
        }
    }

    @Test
    void missingTemplateIsNamed() {
        assertThatThrownBy(() -> renderer.render("no-such-email", "Subject", Map.of()))
                .isInstanceOf(TemplateInputException.class)
                .hasMessageContaining("no-such-email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"verify-email", "password-reset", "sign-up-link", "already-registered"})
    void htmlMatchesItsSnapshot(String template) throws IOException {
        String html = render(template).html();
        Path preview = Path.of("target/mail-preview", template + ".html");
        Files.createDirectories(preview.getParent());
        Files.writeString(preview, html);
        Path snapshot = Path.of("src/test/resources/mail-snapshots", template + ".html");

        assertThat(Files.exists(snapshot) ? Files.readString(snapshot) : "")
                .as("%s differs from %s; if the change is intended, copy %s over it", template, snapshot, preview)
                .isEqualTo(html);
    }
}
