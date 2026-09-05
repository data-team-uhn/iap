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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.statistics.api.MetricValue;

/**
 * Keeps the last set of computed metrics, so that reading them is not a query per metric per page view.
 *
 * <p>
 * Not because computing them is slow — it is not, at the sizes measured — but because of where they are
 * read from. The figures sit on the front page, so every person opening the application would otherwise
 * start a scan of the entire history, and the history only ever grows. The cost of the page would then
 * rise with the age of the deployment, for everyone, forever.
 * </p>
 *
 * <p>
 * <strong>Stale beats slow here, and only here.</strong> These are monthly cohorts: a figure minutes old
 * is the same figure. So a caller that finds the answer out of date waits briefly for a fresh one and
 * takes the previous one if it does not arrive — the page is never held up by a recomputation. The one
 * caller that cannot be served this way is the first after a restart, which has nothing to fall back on
 * and computes in line.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ComputedMetrics
{
    /**
     * One set of metrics and when it was worked out.
     *
     * @param values what the metrics said
     * @param takenAt the nanosecond reading when they were worked out
     * @version $Id$
     * @since 0.1.0
     */
    private record Snapshot(List<MetricValue> values, long takenAt)
    {
    }

    private final ExecutorService worker;

    private final LongSupplier clock;

    /** The last answer, or {@code null} until there has been one. Read from request threads. */
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /** The recomputation in flight, so that ten readers at once start one and not ten. */
    private final AtomicReference<Future<List<MetricValue>>> inFlight = new AtomicReference<>();

    ComputedMetrics(@NotNull final ExecutorService worker, @NotNull final LongSupplier clock)
    {
        this.worker = worker;
        this.clock = clock;
    }

    /**
     * The metrics, worked out again if what is held has gone stale.
     *
     * @param compute how to work them out
     * @param ttlNanos how long an answer stays good for
     * @param waitMillis how long a caller will wait for a fresh answer before taking the previous one
     * @return the metrics
     */
    @NotNull
    List<MetricValue> get(@NotNull final Supplier<List<MetricValue>> compute, final long ttlNanos,
        final long waitMillis)
    {
        final Snapshot held = this.current.get();
        if (held != null && this.clock.getAsLong() - held.takenAt() < ttlNanos) {
            return held.values();
        }
        final Future<List<MetricValue>> running = start(compute);
        // Nothing to fall back on means the first reader after a restart, who waits for a real answer
        // rather than being told there are no metrics - which is what an empty list would say
        return held == null ? await(running) : freshOrHeld(running, held, waitMillis);
    }

    /**
     * A freshly computed answer if one arrives in time, and the previous one if it does not.
     *
     * @param running the recomputation
     * @param held the previous answer
     * @param waitMillis how long to wait for the fresh one
     * @return whichever is available
     */
    private static List<MetricValue> freshOrHeld(final Future<List<MetricValue>> running,
        final Snapshot held, final long waitMillis)
    {
        try {
            return running.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            // Still working. The previous answer is minutes old and these are monthly figures
            return held.values();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return held.values();
        } catch (final ExecutionException e) {
            return held.values();
        }
    }

    /**
     * Waits for a recomputation with no deadline, for the caller that has nothing else to show.
     *
     * @param running the recomputation
     * @return the metrics, empty when it could not be worked out at all — which is the one case where an
     *         empty answer is the honest one, since there is nothing else to say
     */
    private static List<MetricValue> await(final Future<List<MetricValue>> running)
    {
        try {
            return running.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (final ExecutionException e) {
            return List.of();
        }
    }

    /**
     * The recomputation in flight, started if there is not one already. Synchronized so that ten
     * readers arriving together start one recomputation rather than queueing ten.
     *
     * @param compute how to work the metrics out
     * @return the running recomputation
     */
    private synchronized Future<List<MetricValue>> start(final Supplier<List<MetricValue>> compute)
    {
        final Future<List<MetricValue>> running = this.inFlight.get();
        if (running != null && !running.isDone()) {
            return running;
        }
        final Future<List<MetricValue>> started = this.worker.submit(() -> {
            final List<MetricValue> computed = compute.get();
            this.current.set(new Snapshot(computed, this.clock.getAsLong()));
            return computed;
        });
        this.inFlight.set(started);
        return started;
    }
}
