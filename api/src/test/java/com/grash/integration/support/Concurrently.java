package com.grash.integration.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Runs tasks at the same time and collects every outcome, in task order.
 * <p>
 * Each task gets its own thread. The threads wait at a start gate that opens only when all of them are ready, so
 * the tasks really overlap. A task that throws does not stop the others: its exception is kept in its
 * {@link Outcome}. Typical use, N simultaneous API requests:
 * <pre>{@code
 * List<Outcome<Response>> outcomes = Concurrently.run(8, i -> () -> api.call(post("/..."), token));
 * }</pre>
 */
public final class Concurrently {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    private Concurrently() {
    }

    /**
     * Runs {@code count} tasks, the i-th built by {@code task.apply(i)}, within {@link #DEFAULT_TIMEOUT}.
     */
    public static <T> List<Outcome<T>> run(int count, IntFunction<Callable<T>> task) {
        return run(IntStream.range(0, count).mapToObj(task).toList(), DEFAULT_TIMEOUT);
    }

    /**
     * Runs the tasks at the same time.
     *
     * @throws AssertionError if the threads do not start, or the tasks do not finish, within {@code timeout}
     */
    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks, Duration timeout) {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the " + tasks.size() + " threads did not start within " + timeout);
            }
            start.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                outcomes.add(outcome(future, deadline));
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while running the tasks", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Outcome<T> outcome(Future<T> future, long deadline) throws InterruptedException {
        try {
            return new Outcome<>(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a task did not finish in time", e);
        }
    }
}
