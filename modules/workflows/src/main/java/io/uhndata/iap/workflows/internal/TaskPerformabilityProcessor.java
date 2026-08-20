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

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;
import io.uhndata.iap.utils.UserIds;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

/**
 * Says, of each workflow task serialized, whether whoever is asking is among the principals it waits for.
 *
 * <p><strong>Why the server answers this.</strong> A reader has to know which of the things a submission is waiting
 * for are theirs to do — otherwise a page either offers everybody every control, which tells a submitter what a
 * reviewer may do with their request, or guesses. It cannot guess: performers name principals, including groups,
 * and a browser is not told what it belongs to. So the question is answered where the answer lives.</p>
 *
 * <p><strong>The one truth it reads.</strong> The engine resolves a task's performers as it raises it — {@code
 * @creator} becomes the person who raised the host, groups stay group names — and records them on the task. This
 * compares that stored list against the principals the requesting session is bound to, which is exactly what the
 * listing endpoint's {@code @myPrincipals} filter does from the other side. One stored property, two readers, so a
 * dashboard saying something waits for you and a submission page offering the control cannot disagree.</p>
 *
 * <p><strong>It is presentation, never authorization.</strong> Completing a task is refused by the engine against
 * the *definition*, with a service session, admins included; this is a narrower question asked with the reader's
 * own session, so the two can legitimately differ — an administrator is not shown a control for a task naming a
 * group they are not in, and would still be allowed to use it. Nothing may rest on this field being true.</p>
 *
 * <p>Enabled by default, because every reader of a task wants it and none of them should have to remember a
 * selector; ask for {@code -taskPerformability} to serialize a task without it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class TaskPerformabilityProcessor implements ResourceJsonProcessor
{
    /** The field added to each task: whether the reader is among the principals it waits for. */
    static final String FIELD = "@mine";

    /** The property the engine records the resolved principals in. */
    private static final String PERFORMERS = "performers";

    /**
     * The task's JCR type. The same thing as its resource type, said the other way: resource types are
     * slash-separated and node types colon-separated, and only the second is what a node reports being.
     */
    private static final String TASK_NODE_TYPE = "wf:TaskInstance";

    /** Where this applies: a submission's workflow container, one instance in it, or a task on its own. */
    private static final List<String> SERIALIZED_TREES = List.of(WorkflowInstances.RESOURCE_TYPE,
        WorkflowInstance.RESOURCE_TYPE, TaskInstance.RESOURCE_TYPE);

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
            json.add(FIELD, waitsForTheReader(node));
        } catch (final RepositoryException e) {
            // Answering nothing is the safe direction: a reader that cannot see the field offers no control, which
            // is what it would do for a task that is not theirs. Failing the whole serialization instead would take
            // the submission's page down over a question about one button.
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
     * The principals a task records as waiting for it.
     *
     * <p>A task with none named waits for nobody, which is the same fail-closed reading the engine's own performer
     * check applies: a definition has to say who may act.</p>
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
        // Declared multiple, but the type also carries a residual, so a single-valued write is possible
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
