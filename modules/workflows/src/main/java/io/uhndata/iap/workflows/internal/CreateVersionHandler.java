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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Opens a new version of a workflow, for the step after it to mark a draft, carrying whatever diagram the
 * request brought. The workflow is the one an
 * earlier step of the same run created, when there is one, and otherwise the event's target: so {@code createEntity}
 * followed by this step creates a workflow and its first version in one commit, and this step alone adds a version
 * to an existing workflow.
 *
 * <p>The version and its diagram are created in one write — the version first, then the file beneath it — so a
 * draft with no diagram is never an observable state. A client can't do this by posting directly: Sling creates
 * the node a file part's path implies before applying {@code jcr:primaryType}, which would leave a
 * {@code sling:Folder} behind.</p>
 *
 * <p>The draft is marked {@link WorkflowVersion#isBpmnAuthoritative() bpmnAuthoritative}: a version authored this
 * way starts from whatever diagram arrived and has no hand-written flow nodes for a reparse to throw away, so the
 * diagram is the only thing its graph could come from.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "createWorkflowVersion";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource definition = hostOf(context);
        final String label = VersionEdits.newLabel(context, definition);
        if (VersionEdits.hasVersionLabelled(definition, label)) {
            throw new InvalidStateException("This workflow already has a version " + label);
        }
        final Map<String, Object> properties = new HashMap<>();
        properties.put(VersionEdits.PRIMARY_TYPE, VersionEdits.WORKFLOW_VERSION_TYPE);
        properties.put(VersionEdits.VERSION, label);
        properties.put(VersionEdits.BPMN_AUTHORITATIVE, true);
        final String description = Payloads.text(context.getEvent(), VersionEdits.DESCRIPTION);
        if (description != null) {
            properties.put(VersionEdits.DESCRIPTION, description);
        }
        final Resource version = context.getResourceResolver().create(definition,
            VersionEdits.availableName(definition), properties);
        final EventAttachment diagram = Payloads.attachment(context.getEvent(), VersionEdits.BPMN_FILE);
        if (diagram != null) {
            VersionEdits.storeDiagram(version, diagram, context.getResourceResolver());
        }
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, version.getPath());
    }

    /**
     * The workflow a version is being created in: what an earlier step of this run created, or else the target.
     *
     * @param context the handler's context
     * @return the workflow definition resource
     */
    private static Resource hostOf(final WorkflowTaskContext context)
    {
        final Object created = context.getVariable(WorkflowResult.CREATED_PATH_VARIABLE);
        if (created instanceof String) {
            return Objects.requireNonNull(context.getResourceResolver().getResource((String) created),
                "What this run just created is always readable to it");
        }
        return context.getTarget();
    }
}
