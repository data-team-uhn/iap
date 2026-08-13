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
package io.uhndata.iap.workflows.models;

import java.util.Comparator;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.entities.models.EntityHomepage;

/**
 * A Sling Model wrapping a {@code wf:WorkflowTypesHomepage} node, the root container of the
 * {@code /WorkflowTypes} tree: the vocabulary of everything a workflow graph may be built out of.
 *
 * <p>It documents itself, so the catalogue is served at {@code /WorkflowTypes.doc.json} and
 * {@code /WorkflowTypes.doc.md}. Its heading comes from the {@code title} and {@code description} properties,
 * autocreated from the defaults declared by the {@code wf:WorkflowTypesHomepage} node type and editable by a
 * deployment wanting to reword it. The JSON form is not just prose: it is what the visual BPMN editor reads to build
 * its toolbars, grouped by the {@link FlowNodeType#getDocumentationCategories() categories} declared here, which
 * is why the shape of that output is a contract and not an implementation detail.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {WorkflowTypesHomepage.class, AutoDocumentable.class},
    resourceType = WorkflowTypesHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class WorkflowTypesHomepage extends EntityHomepage implements AutoDocumentable
{
    /** The {@code sling:resourceType} of a {@code wf:WorkflowTypesHomepage} node. */
    public static final String RESOURCE_TYPE = "wf/WorkflowTypesHomepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    /**
     * The whole vocabulary, each entry adapted to the model of its own specific kind, ordered by label so that the
     * editor's toolbars and the documentation read the same way every time.
     *
     * <p>Entries of no {@link #isConcrete kind at all} are left out, the same way {@link FlowNode#isConcrete}
     * leaves out the abstract flow nodes.</p>
     *
     * @return a list of flow node types, empty if the vocabulary has not been installed
     */
    @NotNull
    public List<FlowNodeType> getFlowNodeTypes()
    {
        return this.getChildren(FlowNodeType.RESOURCE_TYPE, FlowNodeType.class).stream()
            .filter(WorkflowTypesHomepage::isConcrete)
            .sorted(Comparator.comparing(FlowNodeType::getDocumentationLabel))
            .toList();
    }

    /**
     * Looks a vocabulary entry up by name, e.g. {@code MessageStartEvent}.
     *
     * @param name the name of the entry, which is the name of its node
     * @return the matching flow node type, or {@code null} if the vocabulary has no entry by that name
     */
    @Nullable
    public FlowNodeType getFlowNodeType(@NotNull final String name)
    {
        final FlowNodeType type = this.getChild(name, FlowNodeType.RESOURCE_TYPE, FlowNodeType.class);
        return type != null && isConcrete(type) ? type : null;
    }

    /**
     * Whether a vocabulary entry is of one of the kinds a model stands for. {@code wf:FlowNodeType} is abstract and
     * the resource type of no model, so an entry carrying it as its own type is of no kind at all: it matches
     * nothing by resource type and would be handed to whichever implementation sorts first rather than rejected.
     *
     * @param type the entry to check
     * @return {@code true} if the entry's own resource type is a concrete one
     */
    private static boolean isConcrete(@NotNull final FlowNodeType type)
    {
        return !FlowNodeType.RESOURCE_TYPE.equals(type.getType());
    }

    @Override
    @NotNull
    public String getDocumentationTitle()
    {
        return this.title;
    }

    @Override
    @Nullable
    public String getDocumentationIntro()
    {
        return this.description;
    }

    @Override
    @NotNull
    public List<? extends DocumentedItem> getDocumentedItems()
    {
        return this.getFlowNodeTypes();
    }
}
