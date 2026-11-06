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
 * What the {@link TagPropagationEditor}s of one commit share: the registered processors grouped by phase, the tag
 * definitions, and the node type answers. The definitions and the node type answers belong to one commit, so a new
 * instance is made for each.
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
     * The processor failures already recorded during this commit. A plain set, since Oak runs one commit's editors
     * on a single thread.
     */
    private final Set<String> recordedFailures = new HashSet<>();

    /**
     * Basic constructor.
     *
     * @param registered all the registered tag processors, in any order
     * @param definitions the tag definitions as this commit leaves them
     * @param nodeTypes the node type answers for this commit
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
     * Whether a processor's failure in a phase is the first one in this commit, and so the one to record.
     *
     * <p>
     * A processor that fails on one node usually fails on every node the commit touches, and recording an error
     * happens on the commit thread. Recording once per commit keeps that cost to one, and makes the error's count a
     * count of commits rather than of nodes.
     * </p>
     *
     * @param processor the processor that failed
     * @param phase the phase it failed in
     * @return {@code true} the first time this is asked about that processor and phase, {@code false} after
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
     * The tag definitions as this commit leaves them.
     *
     * @return the definitions
     */
    public TagDefinitions getDefinitions()
    {
        return this.definitions;
    }

    /**
     * The node type answers for this commit.
     *
     * @return the inspector
     */
    public NodeTypeInspector getNodeTypes()
    {
        return this.nodeTypes;
    }

    /**
     * Whether any registered processor is {@link Scope#ENTITY entity-scoped}. Only then does the editor track the
     * enclosing entities and recompute them whole.
     *
     * @return {@code true} if at least one processor needs a whole entity
     */
    public boolean hasEntityScoped()
    {
        return this.entityScoped;
    }
}
