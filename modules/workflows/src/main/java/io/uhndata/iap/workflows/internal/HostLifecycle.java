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
package io.uhndata.iap.workflows.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.tags.models.TagDefinition;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.FlowNode;

/**
 * Records on the resource a workflow drives what reaching a node meant, by placing that node's tag on it.
 *
 * <p>Where {@link HostAccess} materializes a workflow's declarations as read access, this materializes them as
 * state: a process arriving somewhere is usually the only thing that knows what that means to the thing being
 * processed, and saying so on the node keeps it beside the arc that leads there instead of in a service task whose
 * only job is to write it down. Most often that node is an end event — a decision <em>is</em> a way of finishing —
 * but a user task carries a state too, the one its host is in for as long as the task waits.</p>
 *
 * <p>The state is a tag rather than a property, so it is defined once under {@code /Tags} — with its label, colour
 * and ordering — and read by anything that displays content's state, instead of every reader having to know a
 * vocabulary of bare strings. That also decides the interesting part of the behaviour: placing a state
 * <em>retires</em> the other states, because the categories are what make a set of tags a lifecycle rather than a
 * pile of markers. A submission that has just been approved has to stop being in review, or every reader that asks
 * "what state is this in" gets to pick its own answer.</p>
 *
 * <p>Only tags sharing a category with the one being placed give way. A host is free to carry markers that have
 * nothing to do with this process — and may well be under more than one workflow at once — so anything outside
 * those categories is left exactly as it was.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class HostLifecycle
{
    private HostLifecycle()
    {
    }

    /**
     * Places a flow node's tag on the resource the instance is driving.
     *
     * <p>The tag is placed with the platform's own authority: most lifecycle states are declared as system tags
     * precisely so that nothing but the workflow managing them can claim one. The change is made in memory only,
     * since the engine owns the single commit that the whole delivery either lands or reverts as.</p>
     *
     * @param host the resource the instance is driving
     * @param node the flow node the instance reached, which must name a tag
     * @throws WorkflowException when the host cannot carry tags at all, or not this one
     * @throws PersistenceException when the host cannot be written
     */
    static void record(final Resource host, final FlowNode node) throws WorkflowException, PersistenceException
    {
        final String tagName = node.getHostTag();
        // Every resource adapts to Taggable — the mixin decides what may be tagged, not the model — so this is an
        // assertion rather than a case: a null here would mean the tags module is not in the running system at all
        final Taggable taggable = Objects.requireNonNull(host.adaptTo(Taggable.class),
            "Any resource can be read as taggable content");
        final List<TagDefinition> vocabulary = taggable.getApplicableDefinitions();
        final Set<String> retired = Set.copyOf(vocabulary.stream()
            .filter(definition -> definition.getName().equals(tagName))
            .findFirst()
            .orElseThrow(() -> new WorkflowDefinitionException("The flow node " + node.getElementId()
                + " places the tag " + tagName + " on " + host.getPath()
                + ", which is not a tag that may go there"))
            .getCategories());
        final Set<String> tags = taggable.getTags().stream()
            .filter(name -> !sharesCategory(vocabulary, name, retired))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        tags.add(tagName);
        taggable.setTags(tags, true);
    }

    /**
     * Whether a tag the host already carries belongs to a category the tag being placed is claiming. A tag the
     * vocabulary says nothing about is kept: nothing is known about what it means, so nothing here may retire it.
     *
     * @param vocabulary the definitions of every tag that may go on the host
     * @param name the tag already carried
     * @param categories the categories being claimed
     * @return {@code true} if the carried tag must give way
     */
    private static boolean sharesCategory(final List<TagDefinition> vocabulary, final String name,
        final Set<String> categories)
    {
        return vocabulary.stream()
            .filter(definition -> definition.getName().equals(name))
            .anyMatch(definition -> definition.getCategories().stream().anyMatch(categories::contains));
    }
}
