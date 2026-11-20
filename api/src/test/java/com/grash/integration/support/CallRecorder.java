package com.grash.integration.support;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe log of the calls received by a test double, with optional simulated failures. The doubles extend it.
 * <p>
 * A call is named after the method it stands for ({@code "upload"}, {@code "sendSimpleMessage"}) or, for webhooks,
 * after the event. A failure armed with {@link #failOn(String, RuntimeException)} is thrown by every later matching
 * call, which is still recorded with {@code failed = true}.
 */
public class CallRecorder {

    /**
     * Matches every call in {@link #failOn(String, RuntimeException)}.
     */
    public static final String ANY = "*";

    public record Call(String name, List<Object> arguments, boolean failed) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

    /**
     * Records the call, then throws the armed failure, if any.
     */
    protected void record(String name, Object... arguments) {
        RuntimeException failure = failures.getOrDefault(name, failures.get(ANY));
        calls.add(new Call(name, Collections.unmodifiableList(Arrays.asList(arguments)), failure != null));
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Makes every later call named {@code name} ({@link #ANY} for all) throw {@code failure}.
     */
    public void failOn(String name, RuntimeException failure) {
        failures.put(name, failure);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> calls(String name) {
        return calls.stream().filter(call -> call.name().equals(name)).toList();
    }

    /**
     * Waits until {@code count} calls named {@code name} were recorded, for doubles called from another thread.
     *
     * @throws AssertionError if they are not recorded in time
     */
    public List<Call> awaitCalls(String name, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Call> matching = calls(name);
        while (matching.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + count + " call(s) named " + name + " within " + timeout
                        + ", got " + calls());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + name, e);
            }
            matching = calls(name);
        }
        return matching;
    }

    /**
     * Forgets the recorded calls and the armed failures.
     */
    public void reset() {
        calls.clear();
        failures.clear();
    }
}
