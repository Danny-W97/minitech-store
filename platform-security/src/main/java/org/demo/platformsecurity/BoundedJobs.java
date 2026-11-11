package org.demo.platformsecurity;

import java.util.Set;
import java.util.concurrent.*;

/** Bounded workers and queue, with at most one local queued task per record. */
public final class BoundedJobs implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final Set<Long> active=ConcurrentHashMap.newKeySet();
    private final long budgetMillis;
    public BoundedJobs(String name,int concurrency,int queue,long budgetMillis) {
        if (concurrency<1 || concurrency>32 || queue<1 || queue>1000) throw new IllegalArgumentException("Invalid job pool limits");
        this.budgetMillis=budgetMillis;
        workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queue),runnable -> {
            var thread=new Thread(runnable,name); thread.setDaemon(true); return thread;
        });
    }
    public void submit(long id,Runnable action) {
        if (!active.add(id)) return;
        try { workers.execute(() -> {
            try (var ignored=RequestBudget.open(budgetMillis)) { action.run(); }
            finally { active.remove(id); }
        }); } catch (RejectedExecutionException full) { active.remove(id); }
    }
    public void close() { workers.shutdownNow(); }
}
