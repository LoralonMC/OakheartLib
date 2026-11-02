package dev.oakheart.storage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One daemon thread running tasks in submission order.
 *
 * <ul>
 *   <li>Futures complete on the writer thread. Callbacks that touch Bukkit must hop to the main
 *       thread themselves.</li>
 *   <li>A task that throws is logged at SEVERE, its future completes exceptionally, and the worker
 *       carries on.</li>
 *   <li>After {@link #drain} starts, every submit returns a failed future
 *       ({@link RejectedExecutionException}); none throws.</li>
 * </ul>
 *
 * <p>Plugman disable or reload is unsupported for a plugin whose writer timed out on drain: the
 * plugin's classloader closes after {@code onDisable}, and a task still running can then fail
 * with {@code NoClassDefFoundError}. A restart is the supported path.</p>
 */
public final class SerialWriter {

    private final String threadName;
    private final Logger logger;
    private final Thread thread;

    private final Object lock = new Object();
    private final ArrayDeque<Task<?>> queue = new ArrayDeque<>();
    private final Map<String, Task<?>> pendingByKey = new HashMap<>();
    private boolean accepting = true;

    public SerialWriter(String threadName, Logger logger) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.thread = new Thread(this::work, threadName);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Queues {@code task}. */
    public CompletableFuture<Void> submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        return enqueue(null, () -> {
            task.run();
            return null;
        });
    }

    /** Queues {@code task} and completes its future with the task's result. */
    public <T> CompletableFuture<T> supply(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return enqueue(null, task);
    }

    /**
     * Queues {@code task}, superseding every not-yet-started task submitted under the same key.
     *
     * <p>The new task runs at its own queue position, not the superseded one's. A superseded
     * task never runs, and its future completes with the successor's outcome. Writes and deletes
     * of one resource should share a key, so a stale write can never land after a delete.</p>
     */
    public CompletableFuture<Void> submitLatest(String key, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        return enqueue(key, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Stops accepting work and waits for the queue to run, up to {@code timeout}.
     *
     * <p>On timeout every task that has not started is abandoned: its future completes
     * exceptionally with {@link CancellationException}, and the count is logged at SEVERE. A task
     * already running keeps running on the daemon thread.</p>
     *
     * @return true if every queued task ran
     * @throws IllegalStateException if called from the writer thread itself
     */
    public boolean drain(Duration timeout) {
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("SerialWriter " + threadName + " cannot drain itself");
        }
        synchronized (lock) {
            accepting = false;
            lock.notifyAll();
        }
        try {
            thread.join(Math.max(1, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) {
            return true;
        }
        List<Task<?>> abandoned;
        synchronized (lock) {
            abandoned = new ArrayList<>(queue);
            queue.clear();
            pendingByKey.clear();
        }
        if (abandoned.isEmpty() && !thread.isAlive()) {
            return true; // finished just after the timeout
        }
        for (Task<?> task : abandoned) {
            task.future.completeExceptionally(new CancellationException(
                    "SerialWriter " + threadName + " was drained before this task ran"));
        }
        logger.severe("SerialWriter " + threadName + " did not finish within " + timeout.toMillis()
                + " ms; abandoned " + abandoned.size() + " queued task(s)"
                + (abandoned.isEmpty() ? "" : ", whose writes are lost")
                + ". A task may still be running.");
        return false;
    }

    /** Whether the writer still accepts work (false once {@link #drain} has started). */
    public boolean isAccepting() {
        synchronized (lock) {
            return accepting;
        }
    }

    /** Whether the caller is running on this writer's thread. */
    public boolean isWriterThread() {
        return Thread.currentThread() == thread;
    }

    public String threadName() {
        return threadName;
    }

    private <T> CompletableFuture<T> enqueue(String key, Callable<T> work) {
        Task<T> task = new Task<>(key, work);
        synchronized (lock) {
            if (!accepting) {
                return CompletableFuture.failedFuture(new RejectedExecutionException(
                        "SerialWriter " + threadName + " is draining or drained"));
            }
            if (key != null) {
                Task<?> previous = pendingByKey.put(key, task);
                if (previous != null) {
                    queue.remove(previous);
                    chain(task.future, previous);
                }
            }
            queue.addLast(task);
            lock.notifyAll();
        }
        return task.future;
    }

    @SuppressWarnings("unchecked")
    private static <T> void chain(CompletableFuture<T> successor, Task<?> superseded) {
        CompletableFuture<Object> target = (CompletableFuture<Object>) superseded.future;
        successor.whenComplete((value, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
            } else {
                target.complete(value);
            }
        });
    }

    private void work() {
        while (true) {
            Task<?> task;
            synchronized (lock) {
                while (queue.isEmpty()) {
                    if (!accepting) {
                        return;
                    }
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        // Only drain ends the worker.
                    }
                }
                task = queue.pollFirst();
                if (task.key != null && pendingByKey.get(task.key) == task) {
                    pendingByKey.remove(task.key);
                }
            }
            task.run(logger, threadName);
        }
    }

    private static final class Task<T> {
        final String key;
        final Callable<T> work;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Task(String key, Callable<T> work) {
            this.key = key;
            this.work = work;
        }

        void run(Logger logger, String threadName) {
            T result;
            try {
                result = work.call();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "A task on " + threadName + " failed"
                        + (key != null ? " (key " + key + ")" : ""), t);
                future.completeExceptionally(t);
                return;
            }
            future.complete(result);
        }
    }
}
