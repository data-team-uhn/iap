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

import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Stores the diagram a version was just authored as, replacing whatever it held.
 *
 * <p>Enforces server-side what the editor only declines to offer client-side.</p>
 *
 * <p>A diagram arriving for an active, retired, or trial version would change a process something is already
 * following or relying on. A client-side refusal alone is a convention, not a rule.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SaveDiagramHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "saveWorkflowDiagram";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final WorkflowVersion version = VersionEdits.targetVersion(context);
        if (version.getState() != WorkflowVersion.State.DRAFT) {
            throw new WorkflowConflictException("Only a draft may be edited, and this version is "
                + VersionEdits.name(version.getState())
                + "; return it to a draft, or draft a copy of it, and edit that");
        }
        final EventAttachment diagram = Payloads.attachment(context.getEvent(), VersionEdits.BPMN_FILE);
        if (diagram == null) {
            throw new InvalidPayloadException("A " + VersionEdits.BPMN_FILE + " file is required");
        }
        VersionEdits.storeDiagram(context.getTarget(), diagram, context.getResourceResolver());
    }
}
