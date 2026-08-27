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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Opens a new draft version of the workflow the event targets, carrying whatever diagram the request brought.
 * The natural companion of {@code createEntity} one level down, which creates the workflow itself.
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
        final Resource definition = context.getTarget();
        final String label = Payloads.requireText(context.getEvent(), VersionEdits.VERSION,
            "A version is required, naming the new version");
        if (VersionEdits.hasVersionLabelled(definition, label)) {
            throw new WorkflowConflictException("This workflow already has a version " + label);
        }
        final Map<String, Object> properties = new HashMap<>();
        properties.put(VersionEdits.PRIMARY_TYPE, VersionEdits.WORKFLOW_VERSION_TYPE);
        properties.put(VersionEdits.VERSION, label);
        properties.put(VersionEdits.STATE, WorkflowVersion.State.DRAFT.name());
        properties.put(VersionEdits.BPMN_AUTHORITATIVE, true);
        final String description = Payloads.text(context.getEvent(), VersionEdits.DESCRIPTION);
        if (description != null) {
            properties.put(VersionEdits.DESCRIPTION, description);
        }
        final Resource version = context.getResourceResolver().create(definition,
            VersionEdits.availableName(definition, label), properties);
        final EventAttachment diagram = Payloads.attachment(context.getEvent(), VersionEdits.BPMN_FILE);
        if (diagram != null) {
            VersionEdits.storeDiagram(version, diagram, context.getResourceResolver());
        }
        context.setVariable(WorkflowResult.CREATED_PATH, version.getPath());
    }
}
