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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Says, of each workflow task serialized, whether whoever is asking is among the principals it waits for
 * ({@code @mine}), and whether they may decide it on behalf of someone else ({@code @delegable}). Ported from IAP-88
 * for the IAP-148 demo, with the second field added.
 *
 * <p>Presentation, never authorization: the engine still refuses whoever may not complete a task, so nothing may rest
 * on either field being true.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class TaskPerformabilityProcessor implements ResourceJsonProcessor
{
    /** Whether the reader is among the principals the task waits for. */
    static final String MINE = "@mine";

    /** Whether the reader may decide the task on behalf of someone else. */
    static final String DELEGABLE = "@delegable";

    /** The property the engine records the task's principals in. */
    private static final String PERFORMERS = "performers";

    private static final String TASK_NODE_TYPE = "wf:TaskInstance";

    /** Where this applies: a submission's workflow container, one instance in it, or a task on its own. */
    private static final List<String> SERIALIZED_TREES = List.of(WorkflowInstances.RESOURCE_TYPE,
        WorkflowInstance.RESOURCE_TYPE, TaskInstance.RESOURCE_TYPE);

    @Reference
    private Delegation delegation;

    @Override
    public String getName()
    {
        return "taskPerformability";
    }

    @Override
    public int getPriority()
    {
        // After `deep` (10), which is what turns a task nested in an instance into JSON at all
        return 20;
    }

    @Override
    public boolean canProcess(@NotNull final Resource resource)
    {
        return SERIALIZED_TREES.stream().anyMatch(resource::isResourceType);
    }

    @Override
    public boolean isEnabledByDefault(@NotNull final Resource resource)
    {
        return true;
    }

    @Override
    public void leave(@NotNull final Node node, @NotNull final JsonObjectBuilder json,
        @NotNull final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (!node.isNodeType(TASK_NODE_TYPE)) {
                return;
            }
            json.add(MINE, waitsForTheReader(node));
            json.add(DELEGABLE, this.delegation.isDelegate(node.getSession()));
        } catch (final RepositoryException e) {
            // Answering nothing offers no control, which is the safe direction
        }
    }

    /**
     * Whether the session reading this task is among the principals it waits for.
     *
     * @param task the task being serialized
     * @return {@code true} if the reader is named, directly or by a principal they act as
     * @throws RepositoryException if the task or the session cannot be read
     */
    private boolean waitsForTheReader(final Node task) throws RepositoryException
    {
        final List<String> principals = UserIds.principalsOf(task.getSession());
        return performersOf(task).stream().anyMatch(principals::contains);
    }

    /**
     * The principals a task records as waiting for it; none named means it waits for nobody.
     *
     * @param task the task to read
     * @return the recorded principal names, empty when the property is absent
     * @throws RepositoryException if the property cannot be read
     */
    private List<String> performersOf(final Node task) throws RepositoryException
    {
        if (!task.hasProperty(PERFORMERS)) {
            return Collections.emptyList();
        }
        final Property performers = task.getProperty(PERFORMERS);
        final Value[] values = performers.isMultiple()
            ? performers.getValues()
            : new Value[] {performers.getValue()};
        final List<String> names = new ArrayList<>(values.length);
        for (final Value value : values) {
            names.add(value.getString());
        }
        return names;
    }
}
