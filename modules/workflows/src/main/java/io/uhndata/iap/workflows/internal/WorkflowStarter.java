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

import java.util.Arrays;
import java.util.Objects;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code startWorkflow} service task: what makes a freshly created entity start living under its workflow.
 *
 * <p>It is built into the engine rather than being a pluggable handler, because starting an instance <em>is</em> the
 * engine's own business; but it is still reached as an ordinary activity in a definition, so which entities get a
 * workflow, and when, stays editable content rather than platform code.</p>
 *
 * <p>Which workflow to start is found by following a chain of reference properties named in the activity's
 * {@code workflowFrom} configuration — for submissions, {@code schemaVersion/workflow}, since it is the schema
 * version a submission answers that decides what it must go through. Naming the chain rather than hard-coding it
 * keeps the workflows module free of any knowledge of what a submission is.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class WorkflowStarter
{
    /** The name an activity uses to ask for this. */
    static final String NAME = "startWorkflow";

    /** The activity property naming the chain of references leading to the workflow version. */
    private static final String WORKFLOW_FROM = "workflowFrom";

    private WorkflowStarter()
    {
    }

    /**
     * Starts the workflow the created entity's data points at, if it points at one.
     *
     * @param context the executing task's context
     * @param performer how the started instance performs any service task it meets
     * @throws WorkflowException when the activity is misconfigured or the workflow cannot be run
     * @throws PersistenceException when the instance cannot be written
     */
    static void execute(final WorkflowTaskContext context, final InstanceRunner.ServiceTaskPerformer performer)
        throws WorkflowException, PersistenceException
    {
        final Object chain = context.getActivity().get(WORKFLOW_FROM);
        if (!(chain instanceof String) || ((String) chain).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " must configure " + WORKFLOW_FROM + ": the reference chain leading to the workflow version");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        final Resource host = host(context, resolver);
        final Resource versionResource = follow(resolver, host, (String) chain);
        if (versionResource == null || !versionResource.isResourceType(WorkflowVersion.RESOURCE_TYPE)) {
            // Nothing to run: an entity whose data names no workflow simply has none, which is not an error
            return;
        }
        final WorkflowVersion version = Objects.requireNonNull(versionResource.adaptTo(WorkflowVersion.class),
            "A wf:WorkflowVersion resource always adapts to its model");
        if (!version.isActive()) {
            throw new WorkflowDefinitionException("The workflow version " + version.getPath()
                + " is not active, so " + host.getPath() + " cannot be put through it");
        }
        new InstanceRunner(resolver, performer, context.getActor()).start(host, version);
        HostAccess.grantReaders(resolver, host, version, context.getActor());
    }

    /**
     * The entity the workflow will drive: whatever this execution has just created, or failing that the resource
     * the event was aimed at.
     *
     * @param context the executing task's context
     * @param resolver the engine's own session
     * @return the host resource
     * @throws WorkflowDefinitionException when the recorded path leads nowhere
     */
    private static Resource host(final WorkflowTaskContext context, final ResourceResolver resolver)
        throws WorkflowDefinitionException
    {
        final Object created = context.getVariable(WorkflowResult.CREATED_PATH);
        if (!(created instanceof String)) {
            return context.getTarget();
        }
        final Resource host = resolver.getResource((String) created);
        if (host == null) {
            throw new WorkflowDefinitionException("Nothing was created at " + created + " to start a workflow on");
        }
        return host;
    }

    /**
     * Follows a chain of reference properties from a starting resource.
     *
     * @param resolver the engine's own session
     * @param from where to start
     * @param chain slash-separated property names, e.g. {@code schemaVersion/workflow}
     * @return the resource at the end of the chain, or {@code null} if any link is missing
     * @throws WorkflowDefinitionException when a link holds something that is not a reference
     */
    private static Resource follow(final ResourceResolver resolver, final Resource from, final String chain)
        throws WorkflowDefinitionException
    {
        Resource current = from;
        for (final String step : Arrays.asList(chain.split("/"))) {
            final String identifier = current.getValueMap().get(step, String.class);
            if (identifier == null) {
                return null;
            }
            current = dereference(resolver, identifier, step);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Resolves one reference by the identifier it holds.
     *
     * @param resolver the engine's own session
     * @param identifier the referenced node's identifier
     * @param property the property it came from, for the error message
     * @return the referenced resource, or {@code null} if the reference dangles
     * @throws WorkflowDefinitionException when the repository cannot be asked
     */
    private static Resource dereference(final ResourceResolver resolver, final String identifier,
        final String property) throws WorkflowDefinitionException
    {
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "The engine's session is always JCR-backed");
        try {
            final Node node = session.getNodeByIdentifier(identifier);
            return node == null ? null : resolver.getResource(node.getPath());
        } catch (final ItemNotFoundException e) {
            return null;
        } catch (final RepositoryException e) {
            throw new WorkflowDefinitionException("The property " + property
                + " does not hold a usable reference: " + e.getMessage());
        }
    }
}
