package org.demo.platformsecurity;

import org.springframework.http.*;
import org.springframework.http.client.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Connect, idle and whole-call limits, including PATCH and a body that never finishes. */
public final class BoundedHttpRequestFactory implements ClientHttpRequestFactory {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(2, runnable -> {
        var thread=new Thread(runnable,"http-deadline"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final int connectMillis, readMillis;
    private final HttpClient client;
    public BoundedHttpRequestFactory(long connectMillis,long readMillis) {
        if (connectMillis<1 || readMillis<1 || connectMillis>300000 || readMillis>300000)
            throw new IllegalArgumentException("HTTP timeouts must be between 1 and 300000 ms");
        this.connectMillis=(int)connectMillis; this.readMillis=(int)readMillis;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMillis))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ClientHttpRequest createRequest(URI uri,HttpMethod method) {
        var budget=RequestBudget.forCall((long)connectMillis+readMillis);
        return new ClientHttpRequest() {
            private final HttpHeaders headers=new HttpHeaders();
            private final Map<String,Object> attributes=new HashMap<>();
            private final ByteArrayOutputStream body=new ByteArrayOutputStream();
            public HttpMethod getMethod() { return method; }
            public URI getURI() { return uri; }
            public HttpHeaders getHeaders() { return headers; }
            public Map<String,Object> getAttributes() { return attributes; }
            public OutputStream getBody() { return body; }
            public ClientHttpResponse execute() throws IOException {
                headers.set(RequestBudget.HEADER,Long.toString(budget.epochMillis()));
                var request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(Math.min(readMillis,budget.remainingMillis())))
                        .method(method.name(),body.size()==0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                headers.forEach((name,values) -> {
                    if (!Set.of("connection","content-length","expect","host","upgrade").contains(name.toLowerCase(Locale.ROOT)))
                        values.forEach(value -> request.header(name,value));
                });
                var responseBody=new AtomicReference<InputStream>();
                var stopped=new AtomicBoolean();
                var pending=client.sendAsync(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                Runnable abort=() -> {
                    stopped.set(true); pending.cancel(true);
                    var input=responseBody.get();
                    if (input!=null) try { input.close(); } catch(IOException ignored) {}
                };
                // A response arriving concurrently with cancellation must also release its subscription.
                pending.thenAccept(response -> {
                    responseBody.set(response.body());
                    if(stopped.get()) try { response.body().close(); } catch(IOException ignored) {}
                });
                ScheduledFuture<?> expiry;
                try { expiry=DEADLINES.schedule(abort,budget.remainingMillis(),TimeUnit.MILLISECONDS); }
                catch(RuntimeException expired) { abort.run(); throw expired; }
                try {
                    var response=pending.get(budget.remainingMillis(),TimeUnit.MILLISECONDS);
                    responseBody.set(response.body());
                    if(stopped.get()) throw new IOException("HTTP request time budget exhausted");
                    var responseHeaders=new HttpHeaders();
                    response.headers().map().forEach((name,values) -> responseHeaders.put(name,new ArrayList<>(values)));
                    var input=new FilterInputStream(response.body()) {
                        public int read() throws IOException { return timedRead(() -> in.read()); }
                        public int read(byte[] bytes,int offset,int length) throws IOException { return timedRead(() -> in.read(bytes,offset,length)); }
                        private int timedRead(Read operation) throws IOException {
                            if(stopped.get()) throw new IOException("HTTP request time budget exhausted");
                            var idle=DEADLINES.schedule(abort,Math.min(readMillis,budget.remainingMillis()),TimeUnit.MILLISECONDS);
                            try { int count=operation.read(); budget.remainingMillis(); return count; }
                            finally { idle.cancel(false); }
                        }
                    };
                    return new ClientHttpResponse() {
                        public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }
                        public String getStatusText() { var status=HttpStatus.resolve(response.statusCode()); return status==null ? "" : status.getReasonPhrase(); }
                        public HttpHeaders getHeaders() { return responseHeaders; }
                        public InputStream getBody() { return input; }
                        public void close() { expiry.cancel(false); abort.run(); }
                    };
                } catch(InterruptedException interrupted) {
                    expiry.cancel(false); abort.run(); Thread.currentThread().interrupt(); throw new IOException("HTTP request interrupted",interrupted);
                } catch(ExecutionException | TimeoutException | CancellationException failure) {
                    expiry.cancel(false); abort.run(); throw new IOException("HTTP request failed or exceeded its time budget",failure);
                } catch(IOException | RuntimeException failure) {
                    expiry.cancel(false); abort.run(); throw failure;
                }
            }
        };
    }
    @FunctionalInterface private interface Read { int read() throws IOException; }
}
