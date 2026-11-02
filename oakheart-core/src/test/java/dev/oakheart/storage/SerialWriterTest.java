package dev.oakheart.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SerialWriterTest {

    private static final Logger LOG = Logger.getLogger("SerialWriterTest");

    private static SerialWriter writer() {
        return new SerialWriter("test-writer", LOG);
    }

    /** Holds the worker until {@link CountDownLatch#countDown()}, so later tasks stay queued. */
    private static CountDownLatch block(SerialWriter writer) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        writer.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void runsInSubmissionOrderOnItsOwnThread() throws Exception {
        SerialWriter writer = writer();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<?> last = null;
        for (int i = 0; i < 200; i++) {
            int n = i;
            last = writer.submit(() -> {
                order.add(n);
                threads.add(Thread.currentThread().getName());
            });
        }
        last.get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 200; i++) {
            assertEquals(i, order.get(i));
        }
        assertTrue(threads.stream().allMatch("test-writer"::equals));
        assertTrue(writer.drain(Duration.ofSeconds(5)));
    }

    @Test
    void supplyCompletesWithTheResult() throws Exception {
        SerialWriter writer = writer();
        assertEquals(42, writer.supply(() -> 42).get(5, TimeUnit.SECONDS));
        writer.drain(Duration.ofSeconds(5));
    }

    @Test
    void submitLatestSupersedesQueuedTasksOfTheSameKeyOnly() throws Exception {
        SerialWriter writer = writer();
        CountDownLatch release = block(writer);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> a = writer.submitLatest("file", () -> ran.add("a"));
        CompletableFuture<Void> other = writer.submitLatest("other", () -> ran.add("other"));
        CompletableFuture<Void> plain = writer.submit(() -> ran.add("plain"));
        CompletableFuture<Void> b = writer.submitLatest("file", () -> ran.add("b"));
        CompletableFuture<Void> c = writer.submitLatest("file", () -> ran.add("c"));
        release.countDown();

        CompletableFuture.allOf(a, other, plain, b, c).get(5, TimeUnit.SECONDS);
        // The survivor runs at its own (last) position; the other key is untouched.
        assertEquals(List.of("other", "plain", "c"), ran);
        assertTrue(writer.drain(Duration.ofSeconds(5)));
    }

    @Test
    void supersededFuturesCompleteWithTheSuccessorsFailure() throws Exception {
        SerialWriter writer = writer();
        CountDownLatch release = block(writer);
        CompletableFuture<Void> first = writer.submitLatest("k", () -> { });
        CompletableFuture<Void> second = writer.submitLatest("k", () -> {
            throw new IllegalStateException("disk full");
        });
        release.countDown();
        CompletionException e = assertThrows(CompletionException.class, first::join);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(second.isCompletedExceptionally());
        writer.drain(Duration.ofSeconds(5));
    }

    @Test
    void aStartedTaskIsNotSuperseded() throws Exception {
        SerialWriter writer = writer();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> first = writer.submitLatest("k", () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.add("first");
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> second = writer.submitLatest("k", () -> ran.add("second"));
        release.countDown();
        CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("first", "second"), ran);
        writer.drain(Duration.ofSeconds(5));
    }

    @Test
    void aThrowingTaskDoesNotStopTheWorker() throws Exception {
        SerialWriter writer = writer();
        CompletableFuture<Void> bad = writer.submit(() -> {
            throw new RuntimeException("boom");
        });
        CompletableFuture<Integer> good = writer.supply(() -> 7);
        assertEquals(7, good.get(5, TimeUnit.SECONDS));
        assertTrue(bad.isCompletedExceptionally());
        writer.drain(Duration.ofSeconds(5));
    }

    @Test
    void drainRunsTheQueueThenRejects() throws Exception {
        SerialWriter writer = writer();
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 50; i++) {
            int n = i;
            writer.submit(() -> ran.add(n));
        }
        assertTrue(writer.drain(Duration.ofSeconds(5)));
        assertEquals(50, ran.size());
        assertFalse(writer.isAccepting());

        CompletableFuture<Void> late = writer.submit(() -> fail("ran after drain"));
        ExecutionExceptionAssert.causeIs(late, RejectedExecutionException.class);
        CompletableFuture<Void> lateLatest = writer.submitLatest("k", () -> fail("ran after drain"));
        ExecutionExceptionAssert.causeIs(lateLatest, RejectedExecutionException.class);
    }

    @Test
    void drainTimeoutAbandonsQueuedTasks() throws Exception {
        SerialWriter writer = writer();
        CountDownLatch release = block(writer);
        CompletableFuture<Void> queued = writer.submit(() -> fail("abandoned task ran"));
        CompletableFuture<Void> superseded = writer.submitLatest("k", () -> fail("superseded task ran"));
        CompletableFuture<Void> latest = writer.submitLatest("k", () -> fail("abandoned task ran"));

        assertFalse(writer.drain(Duration.ofMillis(100)));
        assertThrows(CancellationException.class, queued::join);
        assertThrows(CancellationException.class, latest::join);
        assertThrows(CancellationException.class, superseded::join);

        release.countDown(); // the running task finishes; the worker then exits with nothing queued
    }

    @Test
    void drainFromTheWriterThreadThrows() throws Exception {
        SerialWriter writer = writer();
        CompletableFuture<Void> f = writer.submit(() -> writer.drain(Duration.ofSeconds(1)));
        ExecutionExceptionAssert.causeIs(f, IllegalStateException.class);
        writer.drain(Duration.ofSeconds(5));
    }

    /** Small helper: the future failed with the given cause type. */
    private static final class ExecutionExceptionAssert {
        static void causeIs(CompletableFuture<?> future, Class<? extends Throwable> type) {
            CompletionException e = assertThrows(CompletionException.class,
                    () -> future.orTimeout(5, TimeUnit.SECONDS).join());
            assertInstanceOf(type, e.getCause());
        }
    }
}
