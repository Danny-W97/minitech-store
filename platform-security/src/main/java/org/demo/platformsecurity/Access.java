package org.demo.platformsecurity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Identity is installed by the authentication filter, never taken from actor headers. */
public final class Access {
    public static final String ACTOR = Access.class.getName();
    public static final String ORDER_TOKEN = "X-Order-Access-Token";
    public static final String CHAT_TOKEN = "X-Chat-Access-Token";
    private Access() {}
    public static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
    public static Actor actor() {
        var request = request();
        return request != null && request.getAttribute(ACTOR) instanceof Actor actor ? actor : Actor.ANONYMOUS;
    }
    public static void customer(Long customerId) {
        var actor = actor();
        require(actor.operator() || actor.internal() || customerId != null && customerId.equals(actor.customerId()));
    }
    public static void submittedCustomer(Long customerId) {
        // A guest cannot attach a purchase to an arbitrary registered customer.
        require(customerId == null || Objects.equals(customerId, actor().customerId()));
    }
    public static void owner(Long customerId, String tokenHash, String header) {
        var actor = actor();
        require(actor.operator() || actor.internal()
                || customerId != null && customerId.equals(actor.customerId())
                || customerId == null && matches(tokenHash, header(header)));
    }
    public static String header(String name) { return request() == null ? null : request().getHeader(name); }
    public static String capabilityHash(String header) {
        String token = header(header);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A random access token is required");
        }
        return hash(token);
    }
    public static boolean matches(String storedHash, String token) {
        return storedHash != null && token != null && token.length() <= 128
                && MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII), hash(token).getBytes(StandardCharsets.US_ASCII));
    }
    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static void require(boolean allowed) {
        if (!allowed) throw new ResponseStatusException(actor().authenticated() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                "Access denied");
    }
}
