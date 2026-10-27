package com.hikmethankolay.template.mail;

/**
 * An email ready to queue in the {@link EmailOutbox}.
 *
 * @param subject subject line
 * @param text    plain-text body
 * @param html    HTML alternative of the body, or {@code null} for a plain-text email
 */
public record RenderedMail(String subject, String text, String html) {}
