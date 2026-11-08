package org.demo.apigateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Trust the socket peer first, then walk a bounded XFF chain from right to left. No DNS lookups. */
@Component
public class ClientAddressResolver {
    private final List<Network> trusted;

    public ClientAddressResolver(
            @Value("${gateway.rate-limit.trusted-proxy-cidrs:}") String cidrs,
            @Value("${server.forward-headers-strategy:none}") String forwardingStrategy) {
        if (!"none".equalsIgnoreCase(forwardingStrategy)) {
            throw new IllegalStateException("Gateway requires server.forward-headers-strategy=none; configure trusted-proxy-cidrs instead");
        }
        var networks = new ArrayList<Network>();
        for (String cidr : cidrs.split(",")) {
            if (!cidr.isBlank()) networks.add(Network.parse(cidr.trim()));
        }
        trusted = List.copyOf(networks);
    }

    public String resolve(HttpServletRequest request) {
        InetAddress peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!isTrusted(peer)) return peer.getHostAddress();
        var hops = new ArrayList<InetAddress>();
        var headers = request.getHeaders("X-Forwarded-For");
        int size = 0;
        while (headers != null && headers.hasMoreElements()) {
            String value = headers.nextElement();
            size += value.length();
            if (size > 2048) throw new InvalidForwardedChain();
            for (String entry : value.split(",", -1)) {
                InetAddress address = literal(entry.trim());
                if (address == null || hops.size() >= 16) throw new InvalidForwardedChain();
                hops.add(address);
            }
        }
        InetAddress candidate = peer;
        for (int index = hops.size() - 1; index >= 0; index--) {
            if (!isTrusted(candidate)) break;
            candidate = hops.get(index);
        }
        return candidate.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) { return trusted.stream().anyMatch(network -> network.contains(address)); }

    public static final class InvalidForwardedChain extends IllegalArgumentException {
        public InvalidForwardedChain() { super("Invalid forwarded client address"); }
    }

    private static InetAddress literal(String value) {
        if (value == null || value.isBlank() || value.contains("%") || value.contains("[") || value.contains("]")) return null;
        try { return InetAddress.ofLiteral(value); } catch (IllegalArgumentException exception) { return null; }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                InetAddress ip = literal(parts[0]);
                if (ip == null || parts.length > 2) throw new IllegalArgumentException();
                int bits = ip.getAddress().length * 8;
                int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
                if (prefix < 1 || prefix > bits) throw new IllegalArgumentException();
                return new Network(ip.getAddress(), prefix);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid trusted proxy IP/CIDR; hostnames and /0 are forbidden");
            }
        }
        boolean contains(InetAddress ip) {
            byte[] candidate = ip.getAddress();
            if (candidate.length != address.length) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((candidate[bit / 8] & mask) != (address[bit / 8] & mask)) return false;
            }
            return true;
        }
    }
}
