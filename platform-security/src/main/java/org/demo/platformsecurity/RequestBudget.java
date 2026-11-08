package org.demo.platformsecurity;

/** Shared request/job budget. A received deadline can only shorten the local limit. */
public final class RequestBudget {
    public static final String HEADER = "X-Request-Deadline-Ms";
    private static final ThreadLocal<Limit> CURRENT = new ThreadLocal<>();
    private RequestBudget() {}
    public record Limit(long endNanos, long epochMillis) {
        public int remainingMillis() {
            long remaining = (endNanos-System.nanoTime())/1_000_000;
            if (remaining <= 0) throw new Exceeded();
            return (int)Math.min(Integer.MAX_VALUE,remaining);
        }
    }
    public static Limit forCall(long milliseconds) {
        long now=System.currentTimeMillis();
        var limit=new Limit(System.nanoTime()+milliseconds*1_000_000,now+milliseconds);
        var current=CURRENT.get();
        return current!=null && current.endNanos()<limit.endNanos() ? current : limit;
    }
    public static Scope open(long milliseconds) { return open(milliseconds,null); }
    public static Scope open(long milliseconds, String incoming) {
        if (milliseconds<1 || milliseconds>3_600_000) throw new IllegalArgumentException("Invalid request budget");
        Limit previous=CURRENT.get(), next=forCall(milliseconds);
        if (incoming!=null) {
            long end=Long.parseLong(incoming);
            long now=System.currentTimeMillis();
            long remaining=end<=now ? 0 : Math.min(milliseconds,end-now);
            var remote=new Limit(System.nanoTime()+remaining*1_000_000,Math.min(next.epochMillis(),end));
            if (remote.endNanos()<next.endNanos()) next=remote;
        }
        CURRENT.set(next); return new Scope(previous);
    }
    public static void check() { if (CURRENT.get()!=null) CURRENT.get().remainingMillis(); }
    public static boolean available() { try { check(); return true; } catch (Exceeded expired) { return false; } }
    public static final class Scope implements AutoCloseable {
        private final Limit previous;
        private Scope(Limit previous) { this.previous=previous; }
        public void close() { if (previous==null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static final class Exceeded extends org.springframework.web.client.RestClientException {
        public Exceeded() { super("Request time budget exhausted"); }
    }
}
