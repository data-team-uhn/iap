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
package io.uhndata.iap.tags.internal;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.uhndata.iap.tags.spi.TagDefinitions;
import io.uhndata.iap.tags.spi.TagProcessor;
import io.uhndata.iap.tags.spi.TagProcessor.Phase;
import io.uhndata.iap.tags.spi.TagProcessor.Scope;

/**
 * Everything the {@link TagPropagationEditor}s of one commit share: the registered processors grouped by the phase
 * they run in, the tag definitions in effect, and the node type verdicts. Created once per commit, since the
 * definitions and the type verdicts are snapshots that must not outlive it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagPropagationConfig
{
    private final Map<Phase, List<TagProcessor>> processors = new EnumMap<>(Phase.class);

    private final TagDefinitions definitions;

    private final NodeTypeInspector nodeTypes;

    private final boolean entityScoped;

    /**
     * The processor failures already recorded during this commit. A plain set: one of these is built per commit by
     * {@code TagPropagationEditorProvider.getRootEditor}, and Oak runs an editor on a single thread.
     */
    private final Set<String> recordedFailures = new HashSet<>();

    /**
     * Basic constructor.
     *
     * @param registered all the registered tag processors, in any order
     * @param definitions the tag definitions in effect for this commit
     * @param nodeTypes the node type verdicts for this commit
     */
    public TagPropagationConfig(final List<TagProcessor> registered, final TagDefinitions definitions,
        final NodeTypeInspector nodeTypes)
    {
        this.definitions = definitions;
        this.nodeTypes = nodeTypes;
        this.entityScoped = registered.stream().anyMatch(processor -> processor.getScope() == Scope.ENTITY);
        final Comparator<TagProcessor> byPriority = Comparator.comparingInt(TagProcessor::getPriority);
        for (final Phase phase : Phase.values()) {
            this.processors.put(phase, registered.stream()
                .filter(processor -> processor.getPhase() == phase)
                .sorted(byPriority)
                .toList());
        }
    }

    /**
     * Whether a processor's failure in a phase is the first one this commit has seen, and so the one worth
     * recording.
     *
     * <p>
     * A commit can touch thousands of nodes, and a processor broken enough to fail on one usually fails on all of
     * them. The repository would survive that — recordings of one fault deduplicate onto a single node — but the
     * work of getting there happens on the commit thread, once per node. One per commit is also the more useful
     * record: the occurrence count then counts commits that were damaged rather than nodes that were visited.
     * </p>
     *
     * @param processor the processor that failed
     * @param phase the phase it failed in
     * @return {@code true} the first time this is asked about that pair, {@code false} every time after
     */
    public boolean isFirstFailure(final TagProcessor processor, final Phase phase)
    {
        return this.recordedFailures.add(processor.getClass().getName() + '#' + phase.name());
    }

    /**
     * The processors running in one phase, in invocation order.
     *
     * @param phase a traversal phase
     * @return the processors, an empty list if none run in that phase
     */
    public List<TagProcessor> getProcessors(final Phase phase)
    {
        return this.processors.get(phase);
    }

    /**
     * The tag definitions in effect for this commit.
     *
     * @return the definitions
     */
    public TagDefinitions getDefinitions()
    {
        return this.definitions;
    }

    /**
     * The node type verdicts for this commit.
     *
     * @return the inspector
     */
    public NodeTypeInspector getNodeTypes()
    {
        return this.nodeTypes;
    }

    /**
     * Whether any registered processor is {@link Scope#ENTITY entity-scoped}, and the editor therefore has to track
     * the enclosing entities and recompute them whole. Nothing else pays for that bookkeeping.
     *
     * @return {@code true} if at least one processor needs a whole entity
     */
    public boolean hasEntityScoped()
    {
        return this.entityScoped;
    }
}
