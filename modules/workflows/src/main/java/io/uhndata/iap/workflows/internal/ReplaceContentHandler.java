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
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task replacing one child of what it acts on, the resource the execution created or else the
 * target, with the whole tree the event gives as {@code content}: a JSON object holding the child's properties, its
 * children as objects named by their keys, and each node's type as {@code jcr:primaryType}; or {@code null}, which
 * removes the child. The activity names the {@code child} it replaces and the {@code nodeTypes} the tree may hold;
 * their declarations decide the rest (see {@link ContentTree}): where each node may be, what it must have, and what
 * its properties may hold.
 * The whole tree is checked before anything is written, so content with a structure of its own, such as a condition,
 * changes in one step rather than node by node.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ReplaceContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "replaceContent";

    /** The payload entry holding the new tree. */
    static final String CONTENT_PARAMETER = "content";

    /** The activity property naming the child replaced. */
    static final String CHILD = "child";

    /** The activity property listing the node types the tree may hold. */
    static final String NODE_TYPES = "nodeTypes";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final String child = context.getActivity().get(CHILD, String.class);
        final String[] listed = context.getActivity().get(NODE_TYPES, String[].class);
        if (child == null || listed == null) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " must name the " + CHILD + " it replaces and the " + NODE_TYPES + " it may hold");
        }
        final JsonObject content = EventJson.objectOrNull(context, CONTENT_PARAMETER);
        final Resource host = ExecutionHost.of(context);
        final Node node = Nodes.of(host);
        try {
            final ContentTree tree = content == null ? null
                : ContentTree.of(node, child, content, Set.copyOf(Arrays.asList(listed)));
            VersioningUtils.checkOut(node);
            if (tree != null) {
                tree.writeInto(node);
            } else if (node.hasNode(child)) {
                node.getNode(child).remove();
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot replace " + child + " in " + host.getPath() + ": "
                + e.getMessage(), e);
        }
    }
}
