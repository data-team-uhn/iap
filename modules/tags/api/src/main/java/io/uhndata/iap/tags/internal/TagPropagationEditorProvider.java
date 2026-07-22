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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.commit.EditorProvider;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.tags.spi.TagProcessor;

/**
 * Provides a {@link TagPropagationEditor} for every commit, invoking all the registered {@link TagProcessor}
 * services in ascending priority order, with the tag definitions read from the committed {@code /Tags} state.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(property = "service.ranking:Integer=100")
public class TagPropagationEditorProvider implements EditorProvider
{
    /** All the registered tag processors. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        fieldOption = FieldOption.REPLACE)
    private volatile List<TagProcessor> processors;

    @Override
    public Editor getRootEditor(final NodeState before, final NodeState after, final NodeBuilder builder,
        final CommitInfo info)
    {
        final List<TagProcessor> current = this.processors;
        if (current == null || current.isEmpty()) {
            return null;
        }
        final List<TagProcessor> topDown = new ArrayList<>();
        final List<TagProcessor> bottomUp = new ArrayList<>();
        for (final TagProcessor processor : current) {
            if (processor.getPhase() == TagProcessor.Phase.TOP_DOWN) {
                topDown.add(processor);
            } else {
                bottomUp.add(processor);
            }
        }
        final Comparator<TagProcessor> byPriority = Comparator.comparingInt(TagProcessor::getPriority);
        topDown.sort(byPriority);
        bottomUp.sort(byPriority);
        return new TagPropagationEditor(builder, new TagDefinitionsSnapshot(after.getChildNode("Tags")),
            new TagWritabilityChecker(after), topDown, bottomUp);
    }
}
