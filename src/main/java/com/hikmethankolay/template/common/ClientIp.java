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
}
