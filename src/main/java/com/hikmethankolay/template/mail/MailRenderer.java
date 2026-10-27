package com.hikmethankolay.template.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders emails from the Thymeleaf templates in {@code src/main/resources/mail/}. Each email has an HTML template
 * ({@code <name>.html}, wrapped in {@code layout.html}) and a plain-text twin ({@code <name>.txt}) that use the same
 * variables. Both also get {@code subject} and {@code appName} ({@code spring.application.name}).
 *
 * <p>An email in an optional {@link MailCategory} also gets {@code categoryName}, {@code unsubscribeUrl} and
 * {@code preferencesUrl}, which the layout shows as an unsubscribe footer; the text body gets the same footer. A
 * transactional email never has the footer, even if the model passes these variables.</p>
 *
 * <p>The engine is private to this class, so it never acts as a web view resolver. HTML templates print values
 * with {@code th:text} and {@code th:href}, which escape them; {@code th:utext} and {@code [(...)]} are not used
 * there. A missing template fails with a {@code TemplateInputException} naming it.</p>
 */
@Component
public class MailRenderer {

    private final TemplateEngine engine = new TemplateEngine();

    private final String appName;

    private final String linkBaseUrl;

    /**
     * Creates the renderer.
     *
     * @param appName     the application name shown in every email
     * @param linkBaseUrl the frontend's URL, for the unsubscribe and preferences links
     */
    public MailRenderer(
            @Value("${spring.application.name}") String appName,
            @Value("${app.mail.link-base-url}") String linkBaseUrl) {
        this.appName = appName;
        this.linkBaseUrl = linkBaseUrl;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.getTextTemplateModePatternSpec().addPattern("*.txt");
        resolver.setCheckExistence(true);
        engine.setTemplateResolver(resolver);
    }

    /**
     * Renders the HTML and text bodies of a transactional email.
     *
     * @param template template name without extension, e.g. {@code verify-email}
     * @param subject  subject line, also the HTML {@code <title>}
     * @param model    template variables
     * @return the rendered email
     */
    public RenderedMail render(String template, String subject, Map<String, Object> model) {
        return render(template, subject, model, MailCategory.ACCOUNT, null);
    }

    /**
     * Renders the HTML and text bodies of an email in a category.
     *
     * @param template         template name without extension, e.g. {@code broadcast/announcement}
     * @param subject          subject line, also the HTML {@code <title>}
     * @param model            template variables
     * @param category         the category; an optional one gets the unsubscribe footer
     * @param unsubscribeToken the recipient's token for the category, or {@code null} for a transactional one
     * @return the rendered email
     */
    public RenderedMail render(
            String template,
            String subject,
            Map<String, Object> model,
            MailCategory category,
            String unsubscribeToken) {
        Context context = new Context(Locale.ENGLISH, model);
        context.setVariable("subject", subject);
        context.setVariable("appName", appName);
        boolean footer = category.optional() && unsubscribeToken != null;
        String unsubscribeUrl = footer ? link("/unsubscribe", unsubscribeToken) : null;
        String preferencesUrl = footer ? link("/settings/emails", null) : null;
        context.setVariable("categoryName", footer ? category.title() : null);
        context.setVariable("unsubscribeUrl", unsubscribeUrl);
        context.setVariable("preferencesUrl", preferencesUrl);
        String text = engine.process(template + ".txt", context);
        if (footer) {
            text += "\nUnsubscribe from " + category.title() + ": " + unsubscribeUrl + "\n"
                    + "Manage email preferences: " + preferencesUrl + "\n";
        }
        return new RenderedMail(subject, text, engine.process(template + ".html", context));
    }

    private String link(String path, String token) {
        UriComponentsBuilder link =
                UriComponentsBuilder.fromUriString(linkBaseUrl).path(path);
        return (token == null ? link : link.queryParam("token", token)).toUriString();
    }
}
