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
package io.uhndata.iap.schemas.editing.internal;

import java.util.List;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.LifecycleState;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Moves a schema or a schema version to the state the activity's {@code state} names, {@code active} or
 * {@code retired}.
 *
 * <p>A version goes from draft to active once, and only when {@link PublishCheck} finds nothing wrong; after
 * that it toggles between active and retired. A draft is never retired, it is discarded. A schema is either open
 * or retired, and its versions inherit the latter.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ChangeSchemaStateHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "changeSchemaState";

    /** The activity property naming the state to move to. */
    static final String STATE_PARAMETER = "state";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final LifecycleState state = configuredState(context);
        final Resource target = context.getTarget();
        final SchemaVersion version = SchemaContent.asVersion(target);
        if (version != null) {
            changeVersion(target, version, state);
            return;
        }
        final Schema schema = SchemaContent.asSchema(target);
        if (schema == null) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        if (schema.getState() == state) {
            throw new NoApplicableWorkflowException("The schema is already " + state.getTag());
        }
        SchemaContent.setLifecycle(target, state == LifecycleState.RETIRED ? LifecycleState.RETIRED : null);
    }

    private void changeVersion(final Resource target, final SchemaVersion version, final LifecycleState state)
        throws WorkflowException, PersistenceException
    {
        final LifecycleState current = version.getState();
        if (current == state) {
            throw new NoApplicableWorkflowException("Version " + version.getVersion() + " is already "
                + state.getTag());
        }
        if (current == LifecycleState.DRAFT) {
            if (state == LifecycleState.RETIRED) {
                throw new NoApplicableWorkflowException("A draft cannot be retired; discard it instead");
            }
            final List<String> problems = PublishCheck.problems(target);
            if (!problems.isEmpty()) {
                throw new NoApplicableWorkflowException("Version " + version.getVersion()
                    + " cannot be activated yet: " + String.join("; ", problems) + ".");
            }
        }
        SchemaContent.setLifecycle(target, state);
    }

    private LifecycleState configuredState(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Object state = context.getActivity().get(STATE_PARAMETER);
        if (LifecycleState.ACTIVE.getTag().equals(state)) {
            return LifecycleState.ACTIVE;
        }
        if (LifecycleState.RETIRED.getTag().equals(state)) {
            return LifecycleState.RETIRED;
        }
        throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
            + " must configure a state of active or retired");
    }
}
