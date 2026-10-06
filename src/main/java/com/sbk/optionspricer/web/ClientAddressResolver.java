package com.sbk.optionspricer.web;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chooses the address used to rate-limit a client. X-Forwarded-For is honoured only when the direct
 * TCP peer is a configured trusted proxy; otherwise any client could pick its own identity.
 */
public final class ClientAddressResolver {

    private static final Pattern IP_LIKE = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddressResolver() {
    }

    /** Parses a comma-separated list of proxy IPs (blank entries ignored). */
    public static Set<String> parseTrustedProxies(String csv) {
        if (csv == null) return Set.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static String resolve(InetSocketAddress remote, String xForwardedFor, Set<String> trustedProxies) {
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        String peer = remote.getAddress().getHostAddress();
        if (xForwardedFor == null || xForwardedFor.isBlank() || trustedProxies == null || !trustedProxies.contains(peer)) {
            return peer;
        }
        // Each trusted hop appends the address it received from, so walk from the right and take the
        // first address that is not one of our own proxies. Anything further left is client-controlled.
        String[] hops = xForwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!IP_LIKE.matcher(hop).matches()) {
                return peer; // malformed chain: do not trust it
            }
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return peer;
    }
}
