/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.statistics.internal;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.statistics.api.MetricValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ComputedMetrics}.
 *
 * <p>The clock is a field this reads rather than the wall clock, and the worker is fenced with a
 * submitted no-op rather than slept on: a test that waits a real two seconds for a real timeout is a
 * test that costs two seconds every run and still races on a slow machine.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class ComputedMetricsTest
{
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final AtomicLong now = new AtomicLong();

    private final AtomicInteger computations = new AtomicInteger();

    @AfterEach
    void tearDown()
    {
        this.worker.shutdownNow();
    }

    @Test
    void worksTheMetricsOutWhenItHasNoneHeld()
    {
        assertEquals(List.of("first"), names(cache().get(this::compute, hours(1), 5_000)));
        assertEquals(1, this.computations.get());
    }

    @Test
    void answersFromWhatItHoldsUntilThatGoesStale()
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);

        this.now.addAndGet(hours(1) / 2);
        cache.get(this::compute, hours(1), 5_000);

        assertEquals(1, this.computations.get(), "a fresh answer should not be worked out again");
    }

    @Test
    void worksThemOutAgainOnceWhatItHoldsIsStale() throws Exception
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);

        this.now.addAndGet(hours(2));
        final List<MetricValue> second = cache.get(this::compute, hours(1), 5_000);

        assertEquals(2, this.computations.get());
        assertEquals(List.of("second"), names(second));
    }

    // The page is never held up by a recomputation: these are monthly figures, so one minutes old is
    // the same figure
    @Test
    void handsBackWhatItHoldsWhenAFreshAnswerIsSlow() throws Exception
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);
        this.now.addAndGet(hours(2));

        final CountDownLatch release = new CountDownLatch(1);
        final List<MetricValue> answered = cache.get(() -> {
            await(release);
            return metrics("slow");
        }, hours(1), 20);

        assertEquals(List.of("first"), names(answered), "the previous answer, not the slow one");
        release.countDown();
        fence();
        // ... and the slow one is what the next reader gets, without waiting for it
        assertEquals(List.of("slow"), names(cache.get(this::compute, hours(1), 5_000)));
    }

    @Test
    void startsOneRecomputationForSeveralReadersAtOnce() throws Exception
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);
        this.now.addAndGet(hours(2));

        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger slowRuns = new AtomicInteger();
        cache.get(() -> {
            slowRuns.incrementAndGet();
            await(release);
            return metrics("slow");
        }, hours(1), 20);
        // Two more readers arrive while that one is still working
        cache.get(this::compute, hours(1), 20);
        cache.get(this::compute, hours(1), 20);
        release.countDown();
        fence();

        assertEquals(1, slowRuns.get(), "one recomputation, however many readers asked");
        assertEquals(1, this.computations.get(), "the later readers joined it rather than starting one");
    }

    // A reader with nothing to fall back on waits for a real answer: an empty list would tell them
    // there are no metrics, which is a different and untrue statement
    @Test
    void waitsForTheFirstAnswerRatherThanSayingThereAreNone()
    {
        final ComputedMetrics cache = cache();

        final List<MetricValue> answered = cache.get(() -> {
            sleepBriefly();
            return metrics("slow");
        }, hours(1), 1);

        assertEquals(List.of("slow"), names(answered));
    }

    @Test
    void saysNothingWhenTheFirstAnswerCannotBeWorkedOutAtAll()
    {
        assertTrue(cache().get(() -> {
            throw new IllegalStateException("the repository is not there");
        }, hours(1), 5_000).isEmpty());
    }

    @Test
    void keepsWhatItHoldsWhenARecomputationFails() throws Exception
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);
        this.now.addAndGet(hours(2));

        final List<MetricValue> answered = cache.get(() -> {
            throw new IllegalStateException("the repository went away");
        }, hours(1), 5_000);

        assertEquals(List.of("first"), names(answered));
    }

    // Both interruption paths, which a caller can force on itself: an interrupted reader gets an
    // answer rather than an exception, and the interrupt is left set for whoever raised it
    @Test
    void handsBackWhatItHoldsToAnInterruptedReader()
    {
        final ComputedMetrics cache = cache();
        cache.get(this::compute, hours(1), 5_000);
        this.now.addAndGet(hours(2));

        final CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        final List<MetricValue> answered = cache.get(() -> {
            await(release);
            return metrics("slow");
        }, hours(1), 5_000);
        final boolean stillInterrupted = Thread.interrupted();
        release.countDown();

        assertEquals(List.of("first"), names(answered));
        assertTrue(stillInterrupted, "the interrupt belongs to whoever raised it");
    }

    @Test
    void saysNothingToAnInterruptedFirstReader()
    {
        final CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        final List<MetricValue> answered = cache().get(() -> {
            await(release);
            return metrics("slow");
        }, hours(1), 5_000);
        final boolean stillInterrupted = Thread.interrupted();
        release.countDown();

        assertTrue(answered.isEmpty());
        assertTrue(stillInterrupted);
    }

    private ComputedMetrics cache()
    {
        return new ComputedMetrics(this.worker, this.now::get);
    }

    private List<MetricValue> compute()
    {
        return metrics(this.computations.getAndIncrement() == 0 ? "first" : "second");
    }

    private static List<MetricValue> metrics(final String name)
    {
        return List.of(MetricValue.of(name, name).valued(1.0, 1).build());
    }

    private static List<String> names(final List<MetricValue> values)
    {
        return values.stream().map(MetricValue::getName).toList();
    }

    private static long hours(final long count)
    {
        return TimeUnit.HOURS.toNanos(count);
    }

    /** Waits for everything already queued on the single worker to finish, FIFO doing the ordering. */
    private void fence() throws Exception
    {
        this.worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static void await(final CountDownLatch latch)
    {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepBriefly()
    {
        try {
            Thread.sleep(30);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
