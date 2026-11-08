package org.demo.platformsecurity;

public record Actor(String role, String service, Long customerId, String email) {
    public static final Actor ANONYMOUS = new Actor("ANONYMOUS", null, null, null);
    public boolean operator() { return "OPERATOR".equals(role); }
    public boolean internal() { return "SERVICE".equals(role); }
    public boolean authenticated() { return !"ANONYMOUS".equals(role); }
}
