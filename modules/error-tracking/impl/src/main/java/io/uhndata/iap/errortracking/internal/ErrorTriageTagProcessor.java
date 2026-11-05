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
 * Computes a recorded error's triage tags from the decisions taken about it.
 *
 * <p>
 * A new decision changes the error node, and this marks the error {@value LoggedError#ACKNOWLEDGED_TAG}, with the
 * decision's resolution. A later occurrence takes the error's count past the count the decision was taken at, and
 * this marks it {@value LoggedError#UNACKNOWLEDGED_TAG} again. No clock and no schedule is involved.
 * </p>
 *
 * <p>
 * It must not throw. It runs on every node that error recording writes, and if it threw, the tags module's editor
 * would leave the error's tags as they were. For the same reason, a set built from repository values never uses
 * {@code Set.of}, which throws on a duplicate.
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

    /** The property holding the error's count when a decision was taken. */
    private static final String DECIDED_AT = "acknowledgedOccurrences";

    /** The property holding when a decision was taken, autocreated by Oak. */
    private static final String CREATED_PROPERTY = "jcr:created";

    /** The node types this processor computes tags for. */
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
        // Every node any commit changes reaches here, so anything else leaves at once. Set.of().contains(null)
        // throws, and an editor does see nodes with no primary type
        final String type = string(error, PRIMARY_TYPE);
        if (type == null || !RECORDED_ERRORS.contains(type)) {
            return Set.of();
        }
        final NodeState latest = latestDecision(error);
        if (latest == null || number(error, "occurrences", 1) > number(latest, DECIDED_AT, 0)) {
            return Set.of(LoggedError.UNACKNOWLEDGED_TAG);
        }
        final String resolution = string(latest, "resolution");
        // Not Set.of: a resolution of `acknowledged` duplicates the first tag
        return resolution == null ? Set.of(LoggedError.ACKNOWLEDGED_TAG)
            : Stream.of(LoggedError.ACKNOWLEDGED_TAG, resolution).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The most recent decision taken about a recorded error: the one taken at the highest count, and among those, the
     * last one created. It must agree with
     * {@link io.uhndata.iap.errortracking.models.Acknowledgement#MOST_RECENT_FIRST}, which the status report uses.
     *
     * @param error the node that records the error
     * @return the decision, or {@code null} if nobody has taken one
     */
    private static NodeState latestDecision(final NodeState error)
    {
        return StreamSupport.stream(error.getChildNodeEntries().spliterator(), false)
            .map(ChildNodeEntry::getNodeState)
            .filter(decision -> ACKNOWLEDGEMENT_TYPE.equals(string(decision, PRIMARY_TYPE)))
            .max(Comparator.comparingLong((NodeState decision) -> number(decision, DECIDED_AT, 0))
                .thenComparingLong(decision -> number(decision, CREATED_PROPERTY, Long.MIN_VALUE)))
            .orElse(null);
    }

    /**
     * Reads a single-valued property as a string.
     *
     * @param node the node to read from
     * @param property the name of the property
     * @return the value, or {@code null} if the property is missing or multi-valued
     */
    private static String string(final NodeState node, final String property)
    {
        final PropertyState value = node.getProperty(property);
        return value == null || value.isArray() ? null : value.getValue(Type.STRING);
    }

    /**
     * Reads a single-valued property as a number. A date reads as milliseconds since the epoch.
     *
     * @param node the node to read from
     * @param property the name of the property
     * @param fallback the value to return if the property is missing, multi-valued, or not a number
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
            // Read as absent, which leaves the error needing attention
            return fallback;
        }
    }
}
