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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.VersionNumbers;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Opens a new version of a workflow, for the step after it to mark a draft, carrying whatever diagram the
 * request brought. The workflow is the one an earlier step of the same run created, when there is one, and otherwise
 * the event's target, as for every built-in service task: so {@code createEntity} followed by this step creates a
 * workflow and its first version in one commit, and this step alone adds a version to an existing workflow. The
 * version is numbered by the platform's rule for versions, {@link VersionNumbers}, as a schema version is, and
 * labelled with the event's {@code version}, or else its number.
 *
 * <p>The version and its diagram are created in one write — the version first, then the file beneath it — so a
 * draft with no diagram is never an observable state. A client can't do this by posting directly: Sling creates
 * the node a file part's path implies before applying {@code jcr:primaryType}, which would leave a
 * {@code sling:Folder} behind.</p>
 *
 * <p>A version opened empty is marked {@link WorkflowVersion#isBpmnAuthoritative() bpmnAuthoritative}: it starts
 * from whatever diagram arrived and has no hand-written flow nodes for a reparse to throw away, so the diagram is the
 * only thing its graph could come from. One the event names a {@code source} for is left for the copy that follows
 * to say, as it says everything else: a hand-written graph copied under a flag set here would be replaced, in the
 * very commit that copies it, by whatever its diagram parses to.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "createWorkflowVersion";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource definition = ExecutionHost.of(context);
        final int number = VersionNumbers.next(definition, WorkflowVersion.RESOURCE_TYPE);
        final String label = label(context, number);
        if (hasVersionLabelled(definition, label)) {
            throw new InvalidStateException("This workflow already has a version " + label);
        }
        final Map<String, Object> properties = new HashMap<>();
        properties.put(VersionEdits.PRIMARY_TYPE, VersionEdits.WORKFLOW_VERSION_TYPE);
        properties.put(VersionEdits.VERSION, label);
        if (context.getEvent().get(CopyContentHandler.SOURCE_PARAMETER) == null) {
            properties.put(VersionEdits.BPMN_AUTHORITATIVE, true);
        }
        final String description = Payloads.text(context.getEvent(), VersionEdits.DESCRIPTION);
        if (description != null) {
            properties.put(VersionEdits.DESCRIPTION, description);
        }
        VersioningUtils.checkOut(definition);
        final Resource version = context.getResourceResolver().create(definition,
            VersionNumbers.nodeName(definition, number), properties);
        final EventAttachment diagram = Payloads.attachment(context.getEvent(), VersionEdits.BPMN_FILE);
        if (diagram != null) {
            VersionEdits.storeDiagram(version, diagram, context.getResourceResolver());
        }
        context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, version.getPath());
    }

    /**
     * The label a new version is created with: the one the event asks for, or else its number. A label that is
     * sent but blank, or not text, is refused rather than defaulted.
     *
     * @param context the handler's context
     * @param number the number the version is named with
     * @return the label, trimmed
     * @throws InvalidPayloadException when the label sent is not usable
     */
    private static String label(final WorkflowTaskContext context, final int number) throws InvalidPayloadException
    {
        if (context.getEvent().get(VersionEdits.VERSION) == null) {
            return VersionNumbers.defaultLabel(number);
        }
        return Payloads.requireText(context.getEvent(), VersionEdits.VERSION, "A version label cannot be blank");
    }

    /**
     * Whether a definition already has a version carrying the given label. Two versions of one workflow carrying
     * the same label would be indistinguishable to everyone reading them.
     *
     * @param definition the workflow definition to look through
     * @param label the version label to look for
     * @return {@code true} if a version already carries that label
     */
    private static boolean hasVersionLabelled(final Resource definition, final String label)
    {
        return StreamSupport.stream(definition.getChildren().spliterator(), false)
            .filter(child -> child.isResourceType(WorkflowVersion.RESOURCE_TYPE))
            .anyMatch(child -> label.equals(child.getValueMap().get(VersionEdits.VERSION, String.class)));
    }
}
