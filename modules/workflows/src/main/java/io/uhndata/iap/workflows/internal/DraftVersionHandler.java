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

import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Opens a new draft beside an existing version, copying the diagram it starts from.
 * This is how an active or retired version is carried forward: neither may be edited in place while instances
 * are following it.
 *
 * <p>Any version may be drafted from, including a draft: that breaks no invariant, it is merely not something
 * the manager offers.</p>
 *
 * <p>{@code bpmnXmlParsedHash} is deliberately not copied, so a draft never claims a parse that has not happened
 * for it — and that missing hash is also what has the commit editor look at the copied diagram in the first
 * place.</p>
 *
 * <p>What happens to the <em>graph</em> follows {@link WorkflowVersion#isBpmnAuthoritative() bpmnAuthoritative},
 * which the copy inherits because it describes how a version was authored rather than a state it moves through.
 * Where the diagram owns the graph, the flow nodes are not copied: the editor derives the whole tree from the
 * diagram that just arrived, in the same commit, and a copied tree would only be waiting to be replaced by the
 * identical one. Where it does not — a version whose flow nodes were authored by hand, because the translation
 * cannot yet carry everything they hold — the graph is copied as it stands, nested as flow nodes nest, extension
 * properties and all: nothing will ever derive it, so a draft without it would be a copy of a process with the
 * process left out.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class DraftVersionHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "draftWorkflowVersion";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource sourceResource = context.getTarget();
        final WorkflowVersion source = VersionEdits.targetVersion(context);
        final Resource definition = VersionEdits.definitionOf(sourceResource);
        final String label = Payloads.requireText(context.getEvent(), VersionEdits.VERSION,
            "A version is required, naming the new version");
        if (VersionEdits.hasVersionLabelled(definition, label)) {
            throw new WorkflowConflictException("This workflow already has a version " + label);
        }
        final Resource draft = context.getResourceResolver().create(definition,
            VersionEdits.availableName(definition, label),
            draftProperties(source, label, Payloads.text(context.getEvent(), VersionEdits.DESCRIPTION)));
        VersionEdits.copyDiagram(source.getBpmnFile(), draft, context.getResourceResolver());
        if (!source.isBpmnAuthoritative()) {
            VersionEdits.copyFlowNodes(sourceResource, draft, context.getResourceResolver());
        }
        context.setVariable(WorkflowResult.CREATED_PATH, draft.getPath());
    }

    /**
     * The properties a new draft starts with: its own label and state, and what carries over from the version it
     * was drafted from.
     *
     * @param source the version being drafted from
     * @param label the new version's label
     * @param description the description asked for, or {@code null} to keep the source's
     * @return the properties to create the draft with
     */
    private static Map<String, Object> draftProperties(final WorkflowVersion source, final String label,
        final String description)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(VersionEdits.PRIMARY_TYPE, VersionEdits.WORKFLOW_VERSION_TYPE);
        properties.put(VersionEdits.VERSION, label);
        properties.put(VersionEdits.STATE, WorkflowVersion.State.DRAFT.name());
        final String newDescription = description == null ? source.getDescription() : description;
        if (newDescription != null) {
            properties.put(VersionEdits.DESCRIPTION, newDescription);
        }
        // A draft of a system workflow handles the same events as the version it came from
        if (source.getTargetResourceType() != null) {
            properties.put(VersionEdits.TARGET_RESOURCE_TYPE, source.getTargetResourceType());
        }
        // bpmnAuthoritative describes how a version was authored, not a state it moves through, so the copy
        // carries it over unchanged.
        if (source.isBpmnAuthoritative()) {
            properties.put(VersionEdits.BPMN_AUTHORITATIVE, true);
        }
        return properties;
    }
}
