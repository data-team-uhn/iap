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
package io.uhndata.iap.errortracking.internal;

import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ErrorTriageTagProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ErrorTriageTagProcessorTest
{
    /** When a decision was taken, autocreated by the repository and used to order decisions taken at the same count. */
    private static final String CREATED = "jcr:created";

    private final ErrorTriageTagProcessor processor = new ErrorTriageTagProcessor();

    @Test
    void computesFromTheNodesOwnContent()
    {
        assertEquals(TagProcessor.Phase.LOCAL, this.processor.getPhase());
        assertEquals(TagProcessor.Scope.NODE, this.processor.getScope());
        assertEquals(100, this.processor.getPriority());
    }

    @Test
    void saysNothingAboutNodesThatAreNotRecordedErrors()
    {
        // Every commit in the repository reaches this processor, so anything else must cost one property read
        assertTrue(this.processor.computeTags(context(node("sub:Submission", 1, null, 0))).isEmpty());
        assertTrue(this.processor.computeTags(context(EmptyNodeState.EMPTY_NODE)).isEmpty());
    }

    @Test
    void anErrorNobodyHasDecidedAboutNeedsAttention()
    {
        assertEquals(Set.of(LoggedError.UNACKNOWLEDGED),
            this.processor.computeTags(context(node("err:LoggedFailure", 3, null, 0))));
    }

    @Test
    void anErrorSomebodyHasDecidedAboutDoesNot()
    {
        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "known-issue"),
            this.processor.computeTags(context(node("err:LoggedFailure", 3, "known-issue", 3))));
    }

    @Test
    void aProblemIsTriagedTheSameWay()
    {
        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "wont-fix"),
            this.processor.computeTags(context(node("err:LoggedProblem", 1, "wont-fix", 1))));
    }

    @Test
    void happeningAgainUndoesTheDecision()
    {
        // The whole reason the decision records a count: a fault that recurs after somebody decided it was fixed
        // asks for attention again by itself, with nothing having to watch the clock
        assertEquals(Set.of(LoggedError.UNACKNOWLEDGED),
            this.processor.computeTags(context(node("err:LoggedFailure", 4, "known-issue", 3))));
    }

    @Test
    void aPlainAcknowledgementIsATriageTagLikeAnyOther()
    {
        // `acknowledged` is one of the shipped triage tags and the acknowledge servlet accepts it, so the markers of
        // such a decision are one tag rather than two — a two-element immutable set would throw on the duplicate,
        // the tag editor would swallow that, and the error would stay unacknowledged however often it was
        // acknowledged
        assertEquals(Set.of(LoggedError.ACKNOWLEDGED),
            this.processor.computeTags(context(node("err:LoggedFailure", 3, LoggedError.ACKNOWLEDGED, 3))));
    }

    @Test
    void theNewestDecisionIsTheOneThatCounts()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 7);
        decide(error, "first", "known-issue", 3);
        decide(error, "second", "wont-fix", 7);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "wont-fix"),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void twoDecisionsTakenAtTheSameCountAreOrderedByWhenTheyWereTaken()
    {
        // Deciding again before the error recurs is ordinary — changing one's mind — and both decisions then carry
        // the same count. The markers have to name the same decision the report names, and the report sorts these by
        // jcr:created
        final NodeBuilder error = builder("err:LoggedFailure", 4);
        decide(error, "decision1", "known-issue", 4).setProperty(CREATED, "2026-08-10T12:00:00.000+00:00", Type.DATE);
        decide(error, "decision2", "wont-fix", 4).setProperty(CREATED, "2026-08-11T12:00:00.000+00:00", Type.DATE);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "wont-fix"),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void theLaterDecisionWinsWhicheverOrderTheChildrenAreIn()
    {
        // The same pair the other way round: nothing about the answer may depend on the order the repository happens
        // to hand the children over in
        final NodeBuilder error = builder("err:LoggedFailure", 4);
        decide(error, "decision1", "known-issue", 4).setProperty(CREATED, "2026-08-11T12:00:00.000+00:00", Type.DATE);
        decide(error, "decision2", "wont-fix", 4).setProperty(CREATED, "2026-08-10T12:00:00.000+00:00", Type.DATE);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "known-issue"),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void aDecisionThatDoesNotSayWhenLosesToOneThatDoes()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 4);
        decide(error, "decision1", "known-issue", 4);
        decide(error, "decision2", "wont-fix", 4).setProperty(CREATED, "2026-08-10T12:00:00.000+00:00", Type.DATE);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "wont-fix"),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void aDecisionSayingNothingStillCountsAsOne()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 1);
        error.child("decision1").setProperty("jcr:primaryType", "err:Acknowledgement")
            .setProperty("acknowledgedOccurrences", 1L);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void ignoresChildrenThatAreNotDecisions()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 1);
        error.child("notes").setProperty("jcr:primaryType", "nt:unstructured")
            .setProperty("acknowledgedOccurrences", 99L);

        assertEquals(Set.of(LoggedError.UNACKNOWLEDGED),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void survivesPropertiesOfTheWrongShape()
    {
        // Nothing here may throw: this processor is the one guaranteed to run over the nodes error recording
        // writes, so a failure in it would be recorded as an error, and recording it would run it again
        final NodeBuilder error = builder("err:LoggedFailure", 1);
        error.setProperty("occurrences", true);
        error.setProperty("jcr:primaryType", "err:LoggedFailure");
        decide(error, "decision1", "known-issue", 1);

        assertEquals(Set.of(LoggedError.ACKNOWLEDGED, "known-issue"),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void survivesAPropertyThatIsSimplyNotThere()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 1);
        error.child("decision1").setProperty("jcr:primaryType", "err:Acknowledgement")
            .setProperty("resolution", "known-issue");

        // A decision that does not say how much had happened counts as having been taken at zero, so anything
        // recorded since puts the error back in front of somebody
        assertEquals(Set.of(LoggedError.UNACKNOWLEDGED),
            this.processor.computeTags(context(error.getNodeState())));
    }

    @Test
    void survivesAMultiValuedPropertyWhereOneWasExpected()
    {
        final NodeBuilder error = builder("err:LoggedFailure", 1);
        error.setProperty("occurrences", java.util.List.of(1L, 2L), org.apache.jackrabbit.oak.api.Type.LONGS);

        assertEquals(Set.of(LoggedError.UNACKNOWLEDGED),
            this.processor.computeTags(context(error.getNodeState())));
    }

    /**
     * A recorded error, optionally with one decision taken about it.
     *
     * @param type the node type
     * @param occurrences how often it has happened
     * @param resolution what was decided, {@code null} for no decision at all
     * @param decidedAt how often it had happened when that was decided
     * @return the node
     */
    private static NodeState node(final String type, final long occurrences, final String resolution,
        final long decidedAt)
    {
        final NodeBuilder error = builder(type, occurrences);
        if (resolution != null) {
            decide(error, "decision1", resolution, decidedAt);
        }
        return error.getNodeState();
    }

    /**
     * A node of the given type that has happened the given number of times.
     *
     * @param type the node type
     * @param occurrences how often it has happened
     * @return a builder for it
     */
    private static NodeBuilder builder(final String type, final long occurrences)
    {
        final NodeBuilder error = EmptyNodeState.EMPTY_NODE.builder();
        error.setProperty("jcr:primaryType", type);
        error.setProperty("occurrences", occurrences);
        return error;
    }

    /**
     * Records a decision about an error.
     *
     * @param error the error being decided about
     * @param name the name of the decision node
     * @param resolution what was decided
     * @param decidedAt how often the error had happened by then
     * @return the decision, so that a test can say when it was taken
     */
    private static NodeBuilder decide(final NodeBuilder error, final String name, final String resolution,
        final long decidedAt)
    {
        return error.child(name).setProperty("jcr:primaryType", "err:Acknowledgement")
            .setProperty("resolution", resolution)
            .setProperty("acknowledgedOccurrences", decidedAt);
    }

    /**
     * A tag context over one node.
     *
     * @param node the node being processed
     * @return the context
     */
    private static TagContext context(final NodeState node)
    {
        final TagContext context = Mockito.mock(TagContext.class);
        Mockito.when(context.getNode()).thenReturn(node);
        return context;
    }
}
