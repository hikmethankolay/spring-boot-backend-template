package com.hikmethankolay.template.webhook;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Posts signed webhook messages. Redirects are not followed (the Standard Webhooks spec counts them as failures),
 * nothing is retried here (the dispatcher schedules retries), the response body is never read, and every connection
 * goes through {@link WebhookAddressPolicy#resolve}, so only public addresses are reached.
 */
@Component
public class WebhookSender implements DisposableBean {

    private final CloseableHttpClient client;

    /**
     * Creates the sender.
     *
     * @param properties timeouts
     * @param addresses  refuses non-public addresses when connecting
     */
    public WebhookSender(WebhookProperties properties, WebhookAddressPolicy addresses) {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return addresses.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        Timeout connect = Timeout.of(properties.connectTimeout());
        Timeout response = Timeout.of(properties.responseTimeout());
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(connect)
                                .setSocketTimeout(response)
                                .build())
                        .build())
                .setDefaultRequestConfig(
                        RequestConfig.custom().setResponseTimeout(response).build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .setUserAgent("Webhooks/1.0")
                .build();
    }

    /**
     * Sends one message.
     *
     * @param url       the endpoint URL
     * @param messageId the {@code webhook-id}
     * @param timestamp the {@code webhook-timestamp}, in seconds
     * @param signature the {@code webhook-signature}
     * @param body      the JSON body
     * @return the receiver's status, or the error if it could not be reached
     */
    public Result send(String url, String messageId, long timestamp, String signature, String body) {
        HttpPost post;
        try {
            post = new HttpPost(url);
        } catch (IllegalArgumentException ex) {
            return new Result(null, "Invalid URL: " + ex.getMessage());
        }
        post.setHeader("webhook-id", messageId);
        post.setHeader("webhook-timestamp", Long.toString(timestamp));
        post.setHeader("webhook-signature", signature);
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
        // Closing the response without reading it drops the connection: a receiver cannot keep us busy with a body.
        try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
            return new Result(response.getCode(), null);
        } catch (IOException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Result(null, message);
        }
    }

    /**
     * Closes the connection pool on shutdown.
     *
     * @throws IOException if closing fails
     */
    @Override
    public void destroy() throws IOException {
        client.close();
    }

    /**
     * The outcome of one attempt.
     *
     * @param status the HTTP status, or {@code null} if no answer came
     * @param error  why no answer came, or {@code null}
     */
    public record Result(Integer status, String error) {

        /**
         * Tells whether the receiver accepted the message.
         *
         * @return {@code true} for a 2xx status
         */
        public boolean succeeded() {
            return status != null && status >= 200 && status < 300;
        }
    }
}
