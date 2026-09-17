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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.extraction.internal.SecondPassPlanner.Batch;
import io.uhndata.iap.llm.LLMCallGate;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.Chunks;
import io.uhndata.iap.submissions.models.File;

/**
 * Step 2: ask again for the fields the first pass could not settle.
 *
 * <p>Two rounds. The targeted round asks each group of fields over the chunks their tags point at that the
 * group has not seen. The sweep then asks whatever is still unanswered over whatever is still unread,
 * ignoring tags, since a field that was not where its tags said probably has a wrong tag.
 *
 * <p>The targeted calls do not depend on each other, so they run at the same time, as many at once as the
 * {@link LLMCallGate} allows. Only the model calls run on other threads. Every write happens on the caller's
 * thread, in batch order, because the caller's resolver is a JCR session and a session is single-threaded.
 * The sweep needs the merged results, so it runs after them.
 *
 * <p>Higher confidence wins when a round finds an answer for a field that already had one. A round never
 * sees the previous answer, so it cannot just agree with it.
 *
 * <p>A call that fails is written down and skipped. The others still count, and the sweep still runs.
 *
 * <p>A document that was never chunked gets nothing. The first pass already sent all of it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SecondPassService.class)
public class SecondPassService
{
    /** How many calls Step 2 may make, sweeps included. Stops a pathological document costing forever. */
    static final int MAX_CALLS = 6;

    /**
     * How long to wait for one call, as a multiple of the provider's timeout. A call may wait that long for a
     * slot, then time out once, then time out again on its one retry.
     */
    static final int CALL_CAP_FACTOR = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger(SecondPassService.class);

    /** Used for the call cap when the LLM settings cannot be read. */
    private static final long FALLBACK_TIMEOUT_SECONDS = 120;

    @Reference
    private AnswerIntakeService intake;

    @Reference
    private LLMConfigurationService configurationService;

    @Reference
    private LLMCallGate callGate;

    private ExecutorService pool;

    /**
     * Start the threads the targeted calls run on: as many as the gate lets through at once. More would only
     * wait for a slot. Sized once; a changed gate takes effect at the next restart.
     */
    @Activate
    protected void activate()
    {
        final AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(this.callGate.getMaxCallsInFlight(), runnable -> {
            final Thread thread = new Thread(runnable, "iap-extraction-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Stop the threads. A call still running is interrupted.
     */
    @Deactivate
    protected void deactivate()
    {
        this.pool.shutdownNow();
    }

    /**
     * Ask again for the fields that need it.
     *
     * @param fileResource the {@code sub:File} node, which holds the call record
     * @param file the same file as a model
     * @param pending the fields to ask about
     * @return what the rounds found, by field name, empty when nothing was asked or nothing was found
     * @throws IOException if the document cannot be read, or the wait for the calls is interrupted
     * @throws PersistenceException if the call record cannot be written
     */
    public Map<String, FieldResult> run(final Resource fileResource, final File file,
        final List<ExtractionField> pending) throws IOException, PersistenceException
    {
        final List<SecondPassPlanner.ChunkFacts> chunks = describe(file);
        if (pending.isEmpty() || chunks.isEmpty()) {
            return Map.of();
        }
        final Limits limits = readLimits();
        final Map<String, FieldResult> best = new HashMap<>();

        final List<Batch> planned = new ArrayList<>(
            SecondPassPlanner.planTargeted(pending, chunks, examined(fileResource, pending), limits.budget()));
        if (planned.size() > MAX_CALLS) {
            LOGGER.warn("Stopping Step 2 for {} after {} calls", file.getPath(), MAX_CALLS);
        }
        final List<Batch> targeted = planned.subList(0, Math.min(planned.size(), MAX_CALLS));
        for (final Asked asked : askAll(file, targeted, limits.callCap())) {
            record(fileResource, asked, LlmCallTracker.EXTRACT, best);
        }

        final List<ExtractionField> stillPending = stillPending(pending, best);
        if (!stillPending.isEmpty() && targeted.size() < MAX_CALLS) {
            final Batch sweep = SecondPassPlanner.planSweep(stillPending, chunks,
                examined(fileResource, stillPending), limits.budget());
            if (sweep != null) {
                final Asked asked = askAll(file, List.of(sweep), limits.callCap()).get(0);
                record(fileResource, asked, LlmCallTracker.SWEEP, best);
            }
        }
        return best;
    }

    /**
     * How long one call may take before it is given up on.
     *
     * @param timeoutSeconds the provider's timeout
     * @return the cap
     */
    static Duration callCapFor(final long timeoutSeconds)
    {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds) * CALL_CAP_FACTOR);
    }

    /** Runs the batches at the same time. Answers in batch order, whatever order they finished in. */
    private List<Asked> askAll(final File file, final List<Batch> batches, final Duration callCap)
        throws IOException
    {
        final List<Future<Asked>> futures = new ArrayList<>();
        for (final Batch batch : batches) {
            futures.add(this.pool.submit(() -> ask(file, batch)));
        }
        final List<Asked> asked = new ArrayList<>();
        try {
            for (int i = 0; i < futures.size(); i++) {
                asked.add(await(futures.get(i), batches.get(i), callCap));
            }
        } catch (final InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Step 2 calls", e);
        }
        return asked;
    }

    /** One call. Always runs on a pool thread, so it touches nothing but the model. */
    private Asked ask(final File file, final Batch batch)
    {
        final long started = System.nanoTime();
        try {
            final IntakeResult result = this.intake.runOver(file, batch.fields(), batch.chunkIds());
            return new Asked(batch, result, LlmCallTracker.elapsedMs(started), null);
        } catch (final IOException e) {
            return new Asked(batch, null, LlmCallTracker.elapsedMs(started), e);
        }
    }

    private static Asked await(final Future<Asked> future, final Batch batch, final Duration callCap)
        throws InterruptedException
    {
        final long started = System.nanoTime();
        try {
            return future.get(callCap.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            return new Asked(batch, null, LlmCallTracker.elapsedMs(started), e.getCause());
        } catch (final TimeoutException e) {
            // Interrupts the call. Its slot is given back and whatever it would have answered is dropped.
            future.cancel(true);
            return new Asked(batch, null, callCap.toMillis(), e);
        }
    }

    /** Writes down one call and folds what it found into the best-so-far. Runs on the caller's thread. */
    private void record(final Resource fileResource, final Asked asked, final String step,
        final Map<String, FieldResult> best) throws PersistenceException
    {
        final Batch batch = asked.batch();
        LlmCallTracker.append(fileResource, step, names(batch.fields()), List.copyOf(batch.chunkIds()),
            asked.durationMs(), outcomeOf(asked));
        final IntakeResult result = asked.result();
        if (result == null) {
            LOGGER.warn("A Step 2 call about {} failed: {}", names(batch.fields()), String.valueOf(asked.failure()));
            return;
        }
        this.intake.applyTags(fileResource, result);
        result.fields().forEach((name, found) -> keepBetter(best, name, found));
    }

    private static String outcomeOf(final Asked asked)
    {
        if (asked.result() == null) {
            return LlmCallTracker.FAILED;
        }
        return asked.result().degraded() ? LlmCallTracker.DEGRADED : LlmCallTracker.OK;
    }

    /** A later round replaces an earlier answer only when it is surer of it. */
    private static void keepBetter(final Map<String, FieldResult> best, final String name,
        final FieldResult found)
    {
        if (!found.found()) {
            return;
        }
        final FieldResult sofar = best.get(name);
        if (sofar == null || found.confidence() > sofar.confidence()) {
            best.put(name, found);
        }
    }

    /** The fields no round has answered well enough yet. */
    private static List<ExtractionField> stillPending(final List<ExtractionField> pending,
        final Map<String, FieldResult> best)
    {
        final List<ExtractionField> left = new ArrayList<>();
        for (final ExtractionField field : pending) {
            final FieldResult found = best.get(field.name());
            if (found == null || found.confidence() < AnswerIntakeService.PENDING_CONFIDENCE) {
                left.add(field);
            }
        }
        return left;
    }

    private static Map<String, Set<String>> examined(final Resource fileResource,
        final List<ExtractionField> fields)
    {
        final Map<String, Set<String>> seen = new HashMap<>();
        for (final ExtractionField field : fields) {
            seen.put(field.name(), LlmCallTracker.examined(fileResource, field.name()));
        }
        return seen;
    }

    /** Every chunk, with what it was placed under and roughly how much text it holds. */
    private static List<SecondPassPlanner.ChunkFacts> describe(final File file) throws IOException
    {
        final Chunks holder = file.getChunks();
        if (holder == null) {
            return List.of();
        }
        final List<SecondPassPlanner.ChunkFacts> facts = new ArrayList<>();
        for (final Chunk chunk : holder.getChunks()) {
            facts.add(new SecondPassPlanner.ChunkFacts(chunk.getName(),
                new HashSet<>(chunk.getRubricTags()),
                ChunkContent.estimateTokens(ChunkContent.readText(chunk))));
        }
        return facts;
    }

    private static List<String> names(final List<ExtractionField> fields)
    {
        final Set<String> names = new LinkedHashSet<>();
        fields.forEach(field -> names.add(field.name()));
        return List.copyOf(names);
    }

    private Limits readLimits()
    {
        try {
            final LLMSettings settings = this.configurationService.getActiveSettings();
            return new Limits(settings.getWholeDocumentTokenLimit(), callCapFor(settings.getTimeoutSeconds()));
        } catch (final IOException e) {
            LOGGER.warn("Could not read the active LLM settings for Step 2, using the defaults: {}", e.getMessage());
            return new Limits(LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT, callCapFor(FALLBACK_TIMEOUT_SECONDS));
        }
    }

    /**
     * What the active LLM settings allow: how many tokens one call may carry, and how long it may take.
     *
     * @param budget the token budget for one call
     * @param callCap how long one call may take
     * @version $Id$
     * @since 0.1.0
     */
    private record Limits(long budget, Duration callCap)
    {
    }

    /**
     * One call and how it went. {@code result} is set when it came back, {@code failure} when it did not.
     *
     * @param batch what was asked
     * @param result what came back, or {@code null}
     * @param durationMs how long it took
     * @param failure why it did not come back, or {@code null}
     * @version $Id$
     * @since 0.1.0
     */
    private record Asked(Batch batch, IntakeResult result, long durationMs, Throwable failure)
    {
    }
}
