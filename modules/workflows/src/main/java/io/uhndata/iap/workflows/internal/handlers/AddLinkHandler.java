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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.models.Linkable;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The {@code addLink} service task: links the host to the content the event names in the entry the activity's
 * {@code to} gives, with a link of the activity's {@code linkType} and its optional {@code linkLabel}. An event naming
 * nothing there links nothing, so a workflow can link as an option, such as a copy to what it was copied from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class AddLinkHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "addLink";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Object entry = context.getActivity().get("to");
        final Object type = context.getActivity().get("linkType");
        if (!(entry instanceof String) || !(type instanceof String)) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " needs a linkType and the event entry it links to");
        }
        final Resource destination = EventPaths.resourceAt(context, (String) entry, "to link to");
        if (destination == null) {
            return;
        }
        final Content content = contentOf(destination);
        final Resource host = ExecutionHost.of(context);
        final Linkable linkable = host.adaptTo(Linkable.class);
        if (linkable == null) {
            // Any resource adapts, except while the models are being registered again
            throw new WorkflowFailedException("Cannot read " + host.getPath() + " as something links can be added to");
        }
        final Object label = context.getActivity().get("linkLabel");
        try {
            linkable.addLink(content, (String) type, label instanceof String ? (String) label : null);
        } catch (final IllegalArgumentException e) {
            throw new InvalidPayloadException("Cannot link " + host.getPath() + " to " + destination.getPath() + ": "
                + e.getMessage());
        } catch (final IllegalStateException e) {
            throw new PersistenceException("Cannot link " + host.getPath() + " to " + destination.getPath(), e);
        }
    }

    /**
     * The content an event names to link to.
     *
     * @param destination what is at the path the event gives
     * @return it, as content
     * @throws WorkflowFailedException when it cannot be read as content
     */
    private static Content contentOf(final Resource destination) throws WorkflowFailedException
    {
        final Content content = destination.adaptTo(Content.class);
        if (content == null) {
            // Any resource adapts, except while the models are being registered again
            throw new WorkflowFailedException("Cannot read " + destination.getPath() + " as content to link to");
        }
        return content;
    }
}
