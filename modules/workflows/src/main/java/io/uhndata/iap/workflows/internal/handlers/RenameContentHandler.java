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

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.utils.move.ContentMover;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task renaming the target to the {@code name} the event gives, keeping its place among its
 * siblings. The name must be one the activity lets content take (see {@link ContentNames}); one a sibling has
 * already is refused rather than replaced by a free one, since the name asked for is the point.
 * The rename is made with the {@link ContentMover}, which keeps what names the target by its path working, and the
 * new path is recorded as what later steps act on.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RenameContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "renameContent";

    @Reference
    private ContentMover mover;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Node node = Nodes.of(target);
        final String name = ContentNames.requested(context.getEvent().get(ContentNames.NAME_PARAMETER),
            context.getActivity().get(ContentNames.NAME_PATTERN, String.class));
        if (name == null) {
            throw new InvalidPayloadException("A new name is needed");
        }
        if (name.equals(target.getName())) {
            context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, target.getPath());
            return;
        }
        try {
            final Node parent = node.getParent();
            ContentNames.checkFree(parent, name);
            VersioningUtils.checkOut(parent);
            context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE,
                this.mover.move(node, parent, name, nextSibling(node)));
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot rename " + target.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The sibling right after a node, which it stays before.
     *
     * @param node the node
     * @return that sibling's name, or {@code null} when the node is last
     * @throws RepositoryException when the siblings cannot be read
     */
    private static String nextSibling(final Node node) throws RepositoryException
    {
        Node previous = null;
        for (final NodeIterator siblings = node.getParent().getNodes(); siblings.hasNext();) {
            final Node sibling = siblings.nextNode();
            if (previous != null && previous.isSame(node)) {
                return sibling.getName();
            }
            previous = sibling;
        }
        return null;
    }
}
