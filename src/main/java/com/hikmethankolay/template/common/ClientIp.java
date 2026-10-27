package com.hikmethankolay.template.common;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

/** Groups client addresses for the per-IP limits (rate limit, login lockout). */
public final class ClientIp {

    private ClientIp() {}

    /**
     * The key a client's requests and failed logins are counted under. An IPv4 address is its own key; an IPv6
     * address counts as its /64 network, because one host usually gets a whole /64 and could use a new address for
     * every request.
     *
     * @param address the client address, as {@code HttpServletRequest.getRemoteAddr()} returns it
     * @return the key; a value that is not an IP address is returned as it is
     */
    public static String limitKey(String address) {
        try {
            // An IPv4-mapped IPv6 address parses as IPv4.
            return switch (InetAddress.ofLiteral(address)) {
                case Inet6Address ipv6 -> HexFormat.of().formatHex(ipv6.getAddress(), 0, 8) + "/64";
                case InetAddress ipv4 -> ipv4.getHostAddress();
            };
        } catch (IllegalArgumentException ex) {
            return address;
        }
    }

    /**
     * Tells whether an address can tell clients apart: a public IP address. Behind a proxy that does not forward the
     * client's address ({@code FORWARD_HEADERS_STRATEGY}), every request seems to come from the proxy's private,
     * loopback or link-local address.
     *
     * @param address the client address, or {@code null}
     * @return {@code false} for {@code null}, a value that is not an IP address, and private, loopback, link-local,
     *     unspecified and IPv6 unique local addresses
     */
    public static boolean isPublic(String address) {
        if (address == null) {
            return false;
        }
        try {
            InetAddress ip = InetAddress.ofLiteral(address);
            boolean uniqueLocal = ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc;
            return !(uniqueLocal
                    || ip.isLoopbackAddress()
                    || ip.isSiteLocalAddress()
                    || ip.isLinkLocalAddress()
                    || ip.isAnyLocalAddress());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * The network a client address belongs to, to recognize a device that moves between nearby addresses: the /24
     * of an IPv4 address and the /64 of an IPv6 address.
     *
     * @param address the client address, as {@code HttpServletRequest.getRemoteAddr()} returns it
     * @return e.g. {@code 203.0.113.0/24}; a value that is not an IP address is returned as it is
     */
    public static String network(String address) {
        try {
            return switch (InetAddress.ofLiteral(address)) {
                case Inet6Address ipv6 -> HexFormat.of().formatHex(ipv6.getAddress(), 0, 8) + "/64";
                case InetAddress ipv4 -> {
                    byte[] bytes = ipv4.getAddress();
                    yield (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + ".0/24";
                }
            };
        } catch (IllegalArgumentException ex) {
            return address;
        }
    }
}
