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

import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Says, of each workflow task serialized, whether whoever is asking may decide it on behalf of someone else
 * ({@code @delegable}). Whether they may decide it themselves is the {@code events} processor's {@code @events}.
 *
 * <p>Presentation, never authorization: the engine still refuses whoever may not complete a task, so nothing may rest
 * on the field being true.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class TaskPerformabilityProcessor implements ResourceJsonProcessor
{
    /** Whether the reader may decide the task on behalf of someone else. */
    static final String DELEGABLE_KEY = "@delegable";

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
            json.add(DELEGABLE_KEY, this.delegation.isDelegate(node.getSession()));
        } catch (final RepositoryException e) {
            // Answering nothing offers no control, which is the safe direction
        }
    }
}
