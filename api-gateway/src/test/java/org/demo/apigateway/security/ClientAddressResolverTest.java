package org.demo.apigateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ClientAddressResolverTest {
    private MockHttpServletRequest request(String peer, String... forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        for (var value : forwarded) request.addHeader("X-Forwarded-For", value);
        return request;
    }
    @Test void untrustedPeersCannotChooseTheirClientAddress() {
        var resolver = new ClientAddressResolver("", "none");
        for (var spoof : new String[]{"203.0.113.9", "invalid-host", "2001:db8::1", "x".repeat(3000)}) {
            assertThat(resolver.resolve(request("198.51.100.7", spoof))).isEqualTo("198.51.100.7");
        }
    }
    @Test void followsTrustedHopsFromTheRightAndIgnoresSpoofedLeftmostIp() {
        var resolver = new ClientAddressResolver("10.0.0.0/24,2001:db8:1::/64", "none");
        assertThat(resolver.resolve(request("10.0.0.2", "203.0.113.99, 198.51.100.7, 10.0.0.8"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("2001:db8:1::2", "203.0.113.99, 198.51.100.7, 10.0.0.8"))).isEqualTo("198.51.100.7");
    }
    @Test void stopsAtUntrustedIntermediaryAndHandlesMultipleHeaderLines() {
        var resolver = new ClientAddressResolver("10.0.0.2/32", "none");
        assertThat(resolver.resolve(request("10.0.0.2", "203.0.113.99", "198.51.100.7, 10.0.0.8"))).isEqualTo("10.0.0.8");
    }
    @Test void canonicalizesIpv6AndIpv4MappedAddresses() {
        var resolver = new ClientAddressResolver("", "none");
        assertThat(resolver.resolve(request("2001:db8::1"))).isEqualTo(resolver.resolve(request("2001:0db8:0:0:0:0:0:1")));
        assertThat(resolver.resolve(request("::ffff:192.0.2.4"))).isEqualTo("192.0.2.4");
    }
    @Test void missingForwardedHeaderKeepsThePeerAndOtherHeaderSchemesAreIgnored() {
        var resolver = new ClientAddressResolver("127.0.0.1", "none");
        var request = request("127.0.0.1");
        request.addHeader("Forwarded", "for=198.51.100.7"); request.addHeader("X-Real-IP", "198.51.100.8");
        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }
    @Test void malformedOrExcessiveTrustedChainsAreRejectedWithoutDns() {
        var resolver = new ClientAddressResolver("127.0.0.1", "none");
        for (var value : new String[]{"example.com", "198.51.100.1,", "[::1]", "fe80::1%eth0", "198.51.100.1:4000",
                "198.51.100.1,".repeat(17) + "198.51.100.2", "x".repeat(2049)}) {
            assertThatThrownBy(() -> resolver.resolve(request("127.0.0.1", value)))
                    .isInstanceOf(ClientAddressResolver.InvalidForwardedChain.class);
        }
    }
    @Test void badProxyConfigurationAndPreprocessedPeerAddressesPreventStartup() {
        for (var cidr : new String[]{"example.com", "0.0.0.0/0", "::/0", "127.0.0.1/33", "2001:db8::/129", "127.0.0.1/", "127.0.0.1/32/1"}) {
            assertThatThrownBy(() -> new ClientAddressResolver(cidr, "none")).isInstanceOf(IllegalStateException.class);
        }
        for (var strategy : new String[]{"native", "framework"}) {
            assertThatThrownBy(() -> new ClientAddressResolver("", strategy)).isInstanceOf(IllegalStateException.class);
        }
    }
}
