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

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.tags.spi.TagContext;
import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Derives a recorded error's triage markers from the decisions taken about it.
 *
 * <p>
 * The markers are computed rather than placed by hand, which is what makes an error un-acknowledge itself. Deciding
 * about an error adds a child recording the decision, and that changes the error node, so this runs and marks it
 * dealt with; recording another occurrence increments its count past what the decision was taken at, so this runs
 * again and marks it as needing attention. The status report follows along without anything having to watch a clock
 * or run on a schedule.
 * </p>
 *
 * <p>
 * Deliberately incapable of failing: it reads three numbers and a string, and it is the one processor guaranteed to
 * run over the nodes error recording itself writes. A processor that threw here would be recorded as an error, and
 * recording it would run it again. That rules out the immutable collection factories for anything whose contents are
 * read from the repository, since they throw on a duplicate.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class ErrorTriageTagProcessor implements TagProcessor
{
    /** The node type of a recorded decision. */
    private static final String ACKNOWLEDGEMENT_TYPE = "err:Acknowledgement";

    /** The property naming a node's type. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /** The property holding how much had happened when a decision was taken. */
    private static final String DECIDED_AT = "acknowledgedOccurrences";

    /** The property holding when a decision was taken, autocreated by the repository. */
    private static final String CREATED = "jcr:created";

    /** The node types this processor has anything to say about. */
    private static final Set<String> RECORDED_ERRORS = Set.of("err:LoggedFailure", "err:LoggedProblem");

    @Override
    public Phase getPhase()
    {
        return Phase.LOCAL;
    }

    @Override
    public int getPriority()
    {
        return 100;
    }

    @Override
    public Set<String> computeTags(final TagContext context)
    {
        final NodeState error = context.getNode();
        // Every commit in the repository reaches here, so leave at once unless this is one of ours. The null check
        // is not redundant: an immutable set throws rather than answering false, and a node with no primary type at
        // all is something an editor does see
        final String type = string(error, PRIMARY_TYPE);
        if (type == null || !RECORDED_ERRORS.contains(type)) {
            return Set.of();
        }
        final NodeState latest = latestDecision(error);
        if (latest == null || number(error, "occurrences", 1) > number(latest, DECIDED_AT, 0)) {
            return Set.of(LoggedError.UNACKNOWLEDGED);
        }
        final String resolution = string(latest, "resolution");
        // A union rather than a two-element Set.of: `acknowledged` is itself one of the shipped triage tags, and a
        // decision resolved that way would otherwise throw on the duplicate. The tag editor swallows what a
        // processor throws, so the error would keep the markers it already had — silently staying unacknowledged
        // however often somebody acknowledged it
        return resolution == null ? Set.of(LoggedError.ACKNOWLEDGED)
            : Stream.of(LoggedError.ACKNOWLEDGED, resolution).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The most recent decision taken about a recorded error: the one taken at the highest occurrence count, and among
     * those the one taken last. Deliberately the same rule as
     * {@link io.uhndata.iap.errortracking.models.Acknowledgement#MOST_RECENT_FIRST}, which is what the report reads —
     * two rules disagreeing would have the markers name one decision while the report named another. The count comes
     * first because it is what a decision is measured against, and because two decisions taken in the same second
     * still order by it.
     *
     * @param error the node recording the error
     * @return the decision, or {@code null} when nobody has taken one
     */
    private static NodeState latestDecision(final NodeState error)
    {
        return StreamSupport.stream(error.getChildNodeEntries().spliterator(), false)
            .map(ChildNodeEntry::getNodeState)
            .filter(decision -> ACKNOWLEDGEMENT_TYPE.equals(string(decision, PRIMARY_TYPE)))
            .max(Comparator.comparingLong((NodeState decision) -> number(decision, DECIDED_AT, 0))
                .thenComparingLong(decision -> number(decision, CREATED, Long.MIN_VALUE)))
            .orElse(null);
    }

    /**
     * Reads a string property, tolerating its absence and its being of the wrong kind.
     *
     * @param node the node to read from
     * @param property the property to read
     * @return the value, or {@code null} when there is no single string there
     */
    private static String string(final NodeState node, final String property)
    {
        final PropertyState value = node.getProperty(property);
        return value == null || value.isArray() ? null : value.getValue(Type.STRING);
    }

    /**
     * Reads a numeric property, tolerating its absence and its being of the wrong kind.
     *
     * @param node the node to read from
     * @param property the property to read
     * @param fallback what to answer when there is no usable number there
     * @return the value, or the fallback
     */
    private static long number(final NodeState node, final String property, final long fallback)
    {
        final PropertyState value = node.getProperty(property);
        if (value == null || value.isArray()) {
            return fallback;
        }
        try {
            return value.getValue(Type.LONG);
        } catch (final RuntimeException e) {
            // A property of the wrong type. Nothing here may throw, and treating it as absent errs towards saying
            // the error still needs attention, which is the safe direction
            return fallback;
        }
    }
}
