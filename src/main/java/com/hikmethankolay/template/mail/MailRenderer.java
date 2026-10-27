package com.hikmethankolay.template.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders emails from the Thymeleaf templates in {@code src/main/resources/mail/}. Each email has an HTML template
 * ({@code <name>.html}, wrapped in {@code layout.html}) and a plain-text twin ({@code <name>.txt}) that use the same
 * variables. Both also get {@code subject} and {@code appName} ({@code spring.application.name}).
 *
 * <p>The engine is private to this class, so it never acts as a web view resolver. HTML templates print values
 * with {@code th:text} and {@code th:href}, which escape them; {@code th:utext} and {@code [(...)]} are not used
 * there. A missing template fails with a {@code TemplateInputException} naming it.</p>
 */
@Component
public class MailRenderer {

    private final TemplateEngine engine = new TemplateEngine();

    private final String appName;

    /**
     * Creates the renderer.
     *
     * @param appName the application name shown in every email
     */
    public MailRenderer(@Value("${spring.application.name}") String appName) {
        this.appName = appName;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.getTextTemplateModePatternSpec().addPattern("*.txt");
        resolver.setCheckExistence(true);
        engine.setTemplateResolver(resolver);
    }

    /**
     * Renders the HTML and text bodies of an email.
     *
     * @param template template name without extension, e.g. {@code verify-email}
     * @param subject  subject line, also the HTML {@code <title>}
     * @param model    template variables
     * @return the rendered email
     */
    public RenderedMail render(String template, String subject, Map<String, Object> model) {
        Context context = new Context(Locale.ENGLISH, model);
        context.setVariable("subject", subject);
        context.setVariable("appName", appName);
        return new RenderedMail(
                subject, engine.process(template + ".txt", context), engine.process(template + ".html", context));
    }
}
