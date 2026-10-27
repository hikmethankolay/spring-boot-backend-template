package com.hikmethankolay.template;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Replaces the SMTP sender with one that keeps every message in memory. */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    @Bean
    @Primary
    CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }

    /**
     * Stores sent messages so tests can read the links they contain. A MIME message is stored as a
     * {@link SimpleMailMessage} holding its text part, so tests read every email the same way; {@link #mime} returns
     * the original for its HTML part and headers.
     */
    public static class CapturingMailSender implements JavaMailSender {

        private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_.-]+)");

        private final List<SimpleMailMessage> messages = new CopyOnWriteArrayList<>();

        private final Map<SimpleMailMessage, MimeMessage> mimeMessages =
                Collections.synchronizedMap(new IdentityHashMap<>());

        private final AtomicInteger failuresLeft = new AtomicInteger();

        private final AtomicReference<Hold> hold = new AtomicReference<>();

        @Override
        public void send(SimpleMailMessage message) {
            capture(message, null);
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            for (SimpleMailMessage message : messages) {
                send(message);
            }
        }

        @Override
        public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Override
        public MimeMessage createMimeMessage(InputStream contentStream) {
            try {
                return new MimeMessage(Session.getInstance(new Properties()), contentStream);
            } catch (MessagingException ex) {
                throw new MailParseException(ex);
            }
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            for (MimeMessage mime : mimeMessages) {
                SimpleMailMessage message = new SimpleMailMessage();
                try {
                    // Sets the Content-Type headers, as the real sender does before sending.
                    mime.saveChanges();
                    message.setTo(Arrays.stream(mime.getAllRecipients())
                            .map(Address::toString)
                            .toArray(String[]::new));
                    message.setSubject(mime.getSubject());
                    message.setText(part(mime, "text/plain"));
                } catch (MessagingException | IOException ex) {
                    throw new MailParseException(ex);
                }
                capture(message, mime);
            }
        }

        private void capture(SimpleMailMessage message, MimeMessage mime) {
            Hold held = hold.get();
            boolean matches = held != null
                    && (held.to() == null
                            || (message.getTo() != null
                                    && List.of(message.getTo()).contains(held.to())));
            if (matches && hold.compareAndSet(held, null)) {
                held.entered().countDown();
                try {
                    // Bounded, so a test that fails before releasing cannot block the outbox for the rest of the suite.
                    held.release().await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new MailSendException("Simulated SMTP outage");
            }
            if (mime != null) {
                mimeMessages.put(message, mime);
            }
            messages.add(message);
        }

        /** Makes the next send count down {@code entered} and then wait for {@code release}, like a slow SMTP server. */
        public void holdNextSend(CountDownLatch entered, CountDownLatch release) {
            hold.set(new Hold(null, entered, release));
        }

        /** Like {@link #holdNextSend}, but holds the next send to {@code to} and lets other sends through. */
        public void holdNextSendTo(String to, CountDownLatch entered, CountDownLatch release) {
            hold.set(new Hold(to, entered, release));
        }

        private record Hold(String to, CountDownLatch entered, CountDownLatch release) {}

        /** Makes the next {@code count} sends fail, like an SMTP outage. */
        public void failNext(int count) {
            failuresLeft.set(count);
        }

        /** Returns the newest message sent to an address with a subject containing the given text. */
        public Optional<SimpleMailMessage> latest(String to, String subjectPart) {
            return messages.reversed().stream()
                    .filter(message ->
                            message.getTo() != null && List.of(message.getTo()).contains(to))
                    .filter(message ->
                            message.getSubject() != null && message.getSubject().contains(subjectPart))
                    .findFirst();
        }

        /** Returns the MIME message a captured message came from; empty for a plain-text send. */
        public Optional<MimeMessage> mime(SimpleMailMessage message) {
            return Optional.ofNullable(mimeMessages.get(message));
        }

        /** Returns the HTML part of a captured message; empty for a plain-text send. */
        public Optional<String> html(SimpleMailMessage message) {
            return mime(message).map(mime -> {
                try {
                    return part(mime, "text/html");
                } catch (MessagingException | IOException ex) {
                    throw new MailParseException(ex);
                }
            });
        }

        /** Extracts the token from the link in a message body. */
        public static String token(SimpleMailMessage message) {
            Matcher matcher = TOKEN.matcher(message.getText());
            if (!matcher.find()) {
                throw new AssertionError("No token link in: " + message.getText());
            }
            return matcher.group(1);
        }

        /** Finds the first part of a MIME type, searching nested multiparts. */
        private static String part(Part part, String mimeType) throws MessagingException, IOException {
            if (part.isMimeType(mimeType)) {
                return (String) part.getContent();
            }
            if (part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    String found = part(multipart.getBodyPart(i), mimeType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
    }
}
