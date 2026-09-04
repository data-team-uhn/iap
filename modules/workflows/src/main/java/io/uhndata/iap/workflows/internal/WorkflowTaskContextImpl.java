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

import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The context handed to service task handlers by the engine: a plain carrier for the pieces of one execution.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class WorkflowTaskContextImpl implements WorkflowTaskContext
{
    private final Resource target;

    private final WorkflowEvent event;

    private final Activity activity;

    private final Map<String, Object> variables;

    private final String actor;

    /**
     * Constructor.
     *
     * @param target the resource the triggering event was aimed at
     * @param event the triggering event
     * @param activity the activity being performed
     * @param variables the execution's shared, mutable variables
     * @param actor the user the execution is acting for
     */
    WorkflowTaskContextImpl(final Resource target, final WorkflowEvent event, final Activity activity,
        final Map<String, Object> variables, final String actor)
    {
        this.target = target;
        this.event = event;
        this.activity = activity;
        this.variables = variables;
        this.actor = actor;
    }

    @Override
    public Resource getTarget()
    {
        return this.target;
    }

    @Override
    public String getActor()
    {
        return this.actor;
    }

    @Override
    public WorkflowEvent getEvent()
    {
        return this.event;
    }

    @Override
    public Activity getActivity()
    {
        return this.activity;
    }

    @Override
    public Object getVariable(final String name)
    {
        return this.variables.get(name);
    }

    @Override
    public void setVariable(final String name, final Object value)
    {
        this.variables.put(name, value);
    }

    @Override
    public ResourceResolver getResourceResolver()
    {
        return this.target.getResourceResolver();
    }

    /**
     * Records who this execution acted for, on whatever it created. The write itself was the engine's, so
     * {@code jcr:createdBy} names the service user and nothing in the repository would otherwise remember the
     * human — which the audit trail, every "things I raised" listing, and {@code @creator} all depend on.
     *
     * <p>Called after every activity rather than once at the end, because it is read <em>within</em> the same walk:
     * {@code createSubmission} creates the submission and a later activity starts its workflow, which resolves
     * {@code @creator} against this property as it raises the first task. Writing it at the end event left that
     * task's recorded performers empty — the definition still admitted the right person, so completing the task
     * worked and nothing noticed until something read the copy. Idempotent, so repeating it costs a property
     * write in a commit that is already open.</p>
     *
     * @throws PersistenceException when the created node cannot be written to
     */
    void recordActor() throws PersistenceException
    {
        final Object created = this.variables.get(WorkflowResult.CREATED_PATH);
        if (!(created instanceof String)) {
            return;
        }
        final Resource resource = Objects.requireNonNull(this.target.getResourceResolver()
            .getResource((String) created), "A handler reported creating something that is not there");
        Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class),
            "A node the engine just created is always modifiable").put("createdBy", this.actor);
    }
}
