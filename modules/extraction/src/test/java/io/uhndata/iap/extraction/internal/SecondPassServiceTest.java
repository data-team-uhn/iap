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
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.llm.LLMCallGate;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SecondPassService}: which calls it makes, what it keeps, and when it stops.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SecondPassServiceTest
{
    private static final String AIMS = "aims";

    private static final String FUNDING = "funding";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    /** The rubric the aims question sits under. */
    private static final String OBJECTIVES = "B.3";

    /** The rubric the funding question sits under. */
    private static final String BUDGET = "B.15";

    private static final String AIMS_TEXT = "## Aims\n\nSomething.\n";

    private static final String FUNDING_TEXT = "## Funding\n\nSomething else.\n";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SecondPassService service;

    private StubIntake intake;

    private SubmissionTree tree;

    private Resource file;

    /** Answers with whatever the test lined up, and remembers what it was asked. Safe to call from many threads. */
    private static final class StubIntake extends AnswerIntakeService
    {
        private final List<List<String>> askedFields = Collections.synchronizedList(new ArrayList<>());

        private final List<Set<String>> askedChunks = Collections.synchronizedList(new ArrayList<>());

        /** Given out in order, when no reply is keyed to the call's first field. */
        private final List<IntakeResult> replies = Collections.synchronizedList(new ArrayList<>());

        /** A reply for a call whose first field is this. Set up before the run, only read during it. */
        private final Map<String, IntakeResult> repliesByField = new HashMap<>();

        /** How long a call whose first field is this takes, once. */
        private final Map<String, Long> delaysMs = new HashMap<>();

        private IOException failure;

        private String explodeOn;

        private CountDownLatch rendezvous;

        @Override
        public IntakeResult runOver(final File file,
            final List<ExtractionField> fields, final Set<String> chunkIds) throws IOException
        {
            final String first = fields.get(0).name();
            if (this.failure != null) {
                throw this.failure;
            }
            if (first.equals(this.explodeOn)) {
                throw new IllegalStateException("not an IOException");
            }
            this.askedFields.add(fields.stream().map(ExtractionField::name).toList());
            this.askedChunks.add(chunkIds);
            pause(first);
            meet();
            final IntakeResult keyed = this.repliesByField.get(first);
            if (keyed != null) {
                return keyed;
            }
            return this.replies.isEmpty()
                ? new IntakeResult(Map.of(), List.of(), List.of(), false)
                : this.replies.remove(0);
        }

        private void pause(final String first) throws IOException
        {
            final Long delay;
            synchronized (this.delaysMs) {
                delay = this.delaysMs.remove(first);
            }
            if (delay == null) {
                return;
            }
            try {
                Thread.sleep(delay);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cut short", e);
            }
        }

        /** Waits for the other call. Fails if it never comes, which is how a serial run shows itself. */
        private void meet() throws IOException
        {
            if (this.rendezvous == null) {
                return;
            }
            this.rendezvous.countDown();
            try {
                if (!this.rendezvous.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("ran alone");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cut short", e);
            }
        }

        @Override
        public void applyTags(final Resource fileResource, final IntakeResult result)
        {
            // Covered by the intake's own tests
        }
    }

    private static IntakeResult found(final String name, final double confidence)
    {
        return new IntakeResult(
            Map.of(name, new FieldResult(name, true, confidence, "an answer", "", List.of())),
            List.of(), List.of(), false);
    }

    private static ExtractionField field(final String name, final String... tags)
    {
        return new ExtractionField(name, "?", "", "Find it.", null, List.of(tags), false);
    }

    /** Lets this many calls through at once, and never makes anyone wait. */
    private static LLMCallGate gate(final int maxCallsInFlight)
    {
        return new LLMCallGate()
        {
            @Override
            public int getMaxCallsInFlight()
            {
                return maxCallsInFlight;
            }

            @Override
            public Permit acquire(final Duration maxWait)
            {
                return () -> {
                };
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.service = new SecondPassService();
        this.intake = new StubIntake();
        inject("intake", this.intake);
        inject("configurationService", (LLMConfigurationService) () ->
            new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(0, 0, 0.0, 0, 1_000_000L, null, null)));
        threads(1);
        this.tree = new SubmissionTree(this.context);
        this.tree.schemaVersion();
        this.tree.submission();
        this.file = this.tree.file("completed");
    }

    @AfterEach
    void tearDown()
    {
        this.service.deactivate();
    }

    /** Runs the targeted calls on this many threads. One makes them run in order, which most tests rely on. */
    private void threads(final int count) throws Exception
    {
        if (count > 1) {
            this.service.deactivate();
        }
        inject("callGate", gate(count));
        this.service.activate();
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = SecondPassService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.service, value);
    }

    private File model()
    {
        return this.file.adaptTo(File.class);
    }

    private void chunk(final String name, final String text, final String... tags)
    {
        final Resource chunk = this.tree.chunk(this.file, name, text);
        if (tags.length > 0) {
            chunk.adaptTo(ModifiableValueMap.class)
                .put(ParsePropertyNames.RUBRIC_TAGS, tags);
        }
    }

    private List<LlmCallTracker.Call> recorded()
    {
        return LlmCallTracker.read(this.file);
    }

    @Test
    void asksNothingWhenNothingIsPending() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);

        assertTrue(this.service.run(this.file, model(), List.of()).isEmpty());
        assertTrue(this.intake.askedFields.isEmpty());
    }

    // The first pass already sent the whole thing
    @Test
    void asksNothingForADocumentWithNoChunks() throws Exception
    {
        assertTrue(this.service.run(this.file, model(), List.of(field(AIMS))).isEmpty());
        assertTrue(this.intake.askedFields.isEmpty());
    }

    @Test
    void asksTheChunksTheFieldsTagsPointAt() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Budget\n\nSomething else.\n", BUDGET);
        this.intake.replies.add(found(AIMS, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(List.of(List.of(AIMS)), this.intake.askedFields);
        assertEquals(Set.of(CHUNK_1), this.intake.askedChunks.get(0));
    }

    @Test
    void recordsEachCallSoALaterOneKnowsWhatWasRead() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.replies.add(found(AIMS, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        final List<LlmCallTracker.Call> calls = recorded();
        assertEquals(1, calls.size());
        assertEquals(LlmCallTracker.EXTRACT, calls.get(0).step());
        assertEquals(List.of(AIMS), calls.get(0).fields());
        assertEquals(List.of(CHUNK_1), calls.get(0).chunks());
        assertEquals(LlmCallTracker.OK, calls.get(0).outcome());
        assertTrue(calls.get(0).durationMs() >= 0);
    }

    @Test
    void recordsAnAnswerThatCouldNotBeReadAsDegraded() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.replies.add(IntakeResult.degraded(List.of(CHUNK_1)));

        assertTrue(this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES))).isEmpty());

        assertEquals(LlmCallTracker.DEGRADED, recorded().get(0).outcome());
    }

    @Test
    void doesNotReadAChunkThisFieldHasAlreadySeen() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## More aims\n\nSomething else.\n", OBJECTIVES);
        LlmCallTracker.append(this.file, LlmCallTracker.INTAKE, List.of(AIMS), List.of(CHUNK_1), 0,
            LlmCallTracker.OK);
        this.intake.replies.add(found(AIMS, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(Set.of(CHUNK_2), this.intake.askedChunks.get(0));
    }

    // Two fields asking about different parts get a call each
    @Test
    void givesEachGroupOfFieldsItsOwnCall() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, FUNDING_TEXT, BUDGET);
        this.intake.replies.add(found(AIMS, 0.9));
        this.intake.replies.add(found(FUNDING, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES), field(FUNDING, BUDGET)));

        assertEquals(List.of(List.of(AIMS), List.of(FUNDING)), this.intake.askedFields);
        assertEquals(Set.of(CHUNK_1), this.intake.askedChunks.get(0));
        assertEquals(Set.of(CHUNK_2), this.intake.askedChunks.get(1));
    }

    @Test
    void runsTheTargetedCallsAtTheSameTime() throws Exception
    {
        threads(2);
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, FUNDING_TEXT, BUDGET);
        this.intake.repliesByField.put(AIMS, found(AIMS, 0.9));
        this.intake.repliesByField.put(FUNDING, found(FUNDING, 0.9));
        this.intake.rendezvous = new CountDownLatch(2);

        final Map<String, FieldResult> best =
            this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES), field(FUNDING, BUDGET)));

        assertEquals(Set.of(AIMS, FUNDING), best.keySet(), "each call met the other, so neither ran alone");
        assertEquals(List.of(LlmCallTracker.OK, LlmCallTracker.OK),
            recorded().stream().map(LlmCallTracker.Call::outcome).toList());
    }

    // The record has to read the same on every run, so it follows the plan, not the race
    @Test
    void recordsTheCallsInBatchOrderWhateverOrderTheyFinishIn() throws Exception
    {
        threads(2);
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, FUNDING_TEXT, BUDGET);
        this.intake.repliesByField.put(AIMS, found(AIMS, 0.9));
        this.intake.repliesByField.put(FUNDING, found(FUNDING, 0.9));
        this.intake.delaysMs.put(AIMS, 300L);

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES), field(FUNDING, BUDGET)));

        assertEquals(List.of(List.of(AIMS), List.of(FUNDING)),
            recorded().stream().map(LlmCallTracker.Call::fields).toList());
    }

    @Test
    void sweepsWhatIsStillUnansweredOverWhatIsStillUnread() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Elsewhere\n\nSomething else.\n", "B.17");
        this.intake.replies.add(new IntakeResult(Map.of(), List.of(), List.of(), false));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(2, this.intake.askedFields.size(), "the targeted call, then the sweep");
        assertEquals(Set.of(CHUNK_2), this.intake.askedChunks.get(1), "the sweep ignores the tags");
        assertEquals(LlmCallTracker.SWEEP, recorded().get(1).step());
    }

    @Test
    void doesNotSweepWhenTheTargetedCallAnsweredEverything() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Elsewhere\n\nSomething else.\n", "B.17");
        this.intake.replies.add(found(AIMS, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(1, this.intake.askedFields.size());
    }

    // A weak answer is still pending, so the sweep runs
    @Test
    void sweepsForAnAnswerItIsNotSureOf() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Elsewhere\n\nSomething else.\n", "B.17");
        this.intake.replies.add(found(AIMS, 0.4));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(2, this.intake.askedFields.size());
    }

    @Test
    void keepsTheSurerOfTwoAnswers() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Elsewhere\n\nSomething else.\n", "B.17");
        this.intake.replies.add(found(AIMS, 0.4));
        this.intake.replies.add(found(AIMS, 0.95));

        final Map<String, FieldResult> best = this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(0.95, best.get(AIMS).confidence());
    }

    @Test
    void doesNotReplaceAnAnswerWithALessSureOne() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, "## Elsewhere\n\nSomething else.\n", "B.17");
        this.intake.replies.add(found(AIMS, 0.8));
        this.intake.replies.add(found(AIMS, 0.2));

        final Map<String, FieldResult> best = this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(0.8, best.get(AIMS).confidence());
    }

    @Test
    void keepsNothingFromACallThatFoundNothing() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.replies.add(new IntakeResult(
            Map.of(AIMS, new FieldResult(AIMS, false, 0.0, null, "", List.of())),
            List.of(), List.of(), false));

        assertTrue(this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES))).isEmpty());
    }

    // A document with many differently-tagged fields must not cost an unbounded number of calls
    @Test
    void stopsAfterTheCallCap() throws Exception
    {
        final List<ExtractionField> fields = new ArrayList<>();
        for (int i = 0; i < SecondPassService.MAX_CALLS + 3; i++) {
            chunk("Chunk-" + i, "## Section " + i + "\n\nSomething.\n", "B." + (i + 1));
            fields.add(field("field" + i, "B." + (i + 1)));
        }

        this.service.run(this.file, model(), fields);

        assertEquals(SecondPassService.MAX_CALLS, this.intake.askedFields.size());
    }

    // The pipeline must not stop because the model did. The call is written down as failed and the run goes on.
    @Test
    void recordsAFailedCallAndCarriesOn() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.failure = new IOException("the model is unreachable");

        final Map<String, FieldResult> best = this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertTrue(best.isEmpty());
        assertEquals(LlmCallTracker.FAILED, recorded().get(0).outcome());
        assertEquals(List.of(CHUNK_1), recorded().get(0).chunks(), "what it was sent is still written down");
    }

    @Test
    void skipsACallThatBlowsUpAndKeepsTheOthers() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        chunk(CHUNK_2, FUNDING_TEXT, BUDGET);
        this.intake.repliesByField.put(AIMS, found(AIMS, 0.9));
        this.intake.explodeOn = FUNDING;

        final Map<String, FieldResult> best =
            this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES), field(FUNDING, BUDGET)));

        assertEquals(Set.of(AIMS), best.keySet());
        assertEquals(LlmCallTracker.OK, recorded().get(0).outcome());
        assertEquals(LlmCallTracker.FAILED, recorded().get(1).outcome());
    }

    // A call that outlives its cap is cut off, so one stuck call cannot hold the whole run
    @Test
    void givesUpOnACallThatOutlivesItsCap() throws Exception
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.delaysMs.put(AIMS, 30_000L);
        final Duration cap = SecondPassService.callCapFor(0);

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(LlmCallTracker.FAILED, recorded().get(0).outcome());
        assertEquals(cap.toMillis(), recorded().get(0).durationMs());
    }

    @Test
    void capsACallAtAMultipleOfTheProviderTimeout()
    {
        assertEquals(Duration.ofSeconds(360), SecondPassService.callCapFor(120));
        assertEquals(Duration.ofSeconds(3), SecondPassService.callCapFor(0), "a missing timeout still gives a cap");
    }

    @Test
    void stopsWhenTheCallerIsInterrupted()
    {
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        Thread.currentThread().interrupt();

        final IOException thrown = assertThrows(IOException.class,
            () -> this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES))));

        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");
        assertTrue(thrown.getMessage().startsWith("Interrupted"), thrown.getMessage());
    }

    @Test
    void fallsBackToTheDefaultsWhenTheSettingsCannotBeRead() throws Exception
    {
        inject("configurationService", (LLMConfigurationService) () -> {
            throw new IOException("no active provider");
        });
        chunk(CHUNK_1, AIMS_TEXT, OBJECTIVES);
        this.intake.replies.add(found(AIMS, 0.9));

        this.service.run(this.file, model(), List.of(field(AIMS, OBJECTIVES)));

        assertEquals(1, this.intake.askedFields.size());
    }
}
