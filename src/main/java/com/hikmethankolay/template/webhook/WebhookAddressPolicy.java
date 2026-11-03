package com.hikmethankolay.template.webhook;

import com.hikmethankolay.template.common.ClientIp;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Keeps webhook deliveries away from internal networks (server-side request forgery): an endpoint URL must be
 * {@code https} on port 443 (or {@code http} on any port unless {@code app.webhooks.require-https}), and every address its host resolves to
 * must be public. The check runs when an endpoint is saved and again on every connection, inside the HTTP client's
 * DNS resolver, so a host that later resolves to an internal address (DNS rebinding) is refused too.
 */
@Component
public class WebhookAddressPolicy {

    private final boolean requireHttps;

    private final boolean allowPrivateAddresses;

    /**
     * Creates the policy.
     *
     * @param properties the webhook settings
     */
    public WebhookAddressPolicy(WebhookProperties properties) {
        this.requireHttps = properties.requireHttps();
        this.allowPrivateAddresses = properties.allowPrivateAddresses();
    }

    /**
     * Checks an endpoint URL.
     *
     * @param url the URL the user entered
     * @return the URL, normalized
     * @throws IllegalArgumentException with a message for the user if it is not allowed
     */
    public String validate(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("The URL is not valid.", ex);
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && !requireHttps)) {
            throw new IllegalArgumentException(
                    requireHttps
                            ? "The URL must start with https://."
                            : "The URL must start with http:// or https://.");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The URL needs a host and must not contain credentials or a fragment.");
        }
        // Production egress (the Kubernetes NetworkPolicy) only allows port 443.
        if (requireHttps && uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("The URL must use the default HTTPS port 443.");
        }
        try {
            resolve(uri.getHost());
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        return uri.normalize().toString();
    }

    /**
     * Resolves a host for a connection, refusing it if any of its addresses is not public.
     *
     * @param host a host name or IP literal
     * @return its addresses
     * @throws UnknownHostException if it does not resolve or resolves to a non-public address
     */
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses;
        try {
            // Also takes bracketed IPv6 literals such as [::1], as URI.getHost() returns them.
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new UnknownHostException("The host " + host + " does not resolve.");
        }
        if (!allowPrivateAddresses) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new UnknownHostException("The host " + host + " resolves to a non-public address.");
                }
            }
        }
        return addresses;
    }

    /**
     * Tells whether an address may receive webhooks.
     *
     * @param address a resolved address
     * @return {@code false} for loopback, private, link-local (including cloud metadata at 169.254.169.254),
     *     unique local, multicast, carrier-grade NAT (100.64.0.0/10), {@code 0.0.0.0/8}, IETF protocol assignments
     *     (192.0.0.0/24), benchmarking (198.18.0.0/15) and reserved (240.0.0.0/4) IPv4 addresses, and for IPv6
     *     addresses that embed an IPv4 address (NAT64, 6to4, Teredo, IPv4-compatible), which a translating network
     *     could turn into any internal IPv4 address
     */
    static boolean isPublic(InetAddress address) {
        if (address.isMulticastAddress() || !ClientIp.isPublic(address.getHostAddress())) {
            return false;
        }
        byte[] ip = address.getAddress();
        int first = ip[0] & 0xff;
        int second = ip[1] & 0xff;
        if (address instanceof Inet4Address) {
            boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127;
            boolean protocolAssignments = first == 192 && second == 0 && ip[2] == 0;
            boolean benchmarking = first == 198 && (second == 18 || second == 19);
            return first != 0 && first < 240 && !carrierGradeNat && !protocolAssignments && !benchmarking;
        }
        return !embedsIpv4(ip);
    }

    /**
     * Tells whether an IPv6 address carries an IPv4 address that a gateway could forward to.
     *
     * @param ip the 16 bytes of an IPv6 address
     * @return {@code true} for 64:ff9b::/96 and 64:ff9b:1::/48 (NAT64), 2002::/16 (6to4), 2001::/32 (Teredo) and
     *     ::/96 (IPv4-compatible)
     */
    private static boolean embedsIpv4(byte[] ip) {
        int group0 = ((ip[0] & 0xff) << 8) | (ip[1] & 0xff);
        int group1 = ((ip[2] & 0xff) << 8) | (ip[3] & 0xff);
        int group2 = ((ip[4] & 0xff) << 8) | (ip[5] & 0xff);
        boolean firstTwelveBytesZero = true;
        for (int i = 0; i < 12; i++) {
            firstTwelveBytesZero &= ip[i] == 0;
        }
        return firstTwelveBytesZero
                || group0 == 0x2002
                || (group0 == 0x2001 && group1 == 0)
                || (group0 == 0x0064 && group1 == 0xff9b && (group2 == 0 || group2 == 1));
    }
}
