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

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.utils.move.ContentMover;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task moving the target, with everything under it: into the node the event names as
 * {@code parent}, or within its own parent when it names none, before the sibling it names as {@code before}, or
 * else last, keeping its name or, where the new parent has it already, taking a free one. The new parent's type must
 * declare it holds the target's type, as for {@code createContent} (see
 * {@link ContentTypes}); nothing moves into itself; and when the activity names a resource type as {@code within},
 * the target stays inside the same nearest ancestor of that type, such as the schema version a question belongs to.
 * The move is made with the {@link ContentMover}, which keeps what names the target by its path working, and the new
 * path is recorded as what later steps act on. An activity naming an {@code orderProperty} has the target and its
 * siblings of its type numbered by their places in it (see {@link Placement}).
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class MoveContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "moveContent";

    /** The payload entry naming where the target goes. */
    static final String PARENT_PARAMETER = "parent";

    /** The activity property naming the type of the ancestor the target stays inside. */
    static final String WITHIN = "within";

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
        try {
            final Resource parent = parent(context, target);
            final Node parentNode = Nodes.of(parent);
            // At or under the target: "/a/b/" starts with "/a/", and "/a/" with itself
            if ((parent.getPath() + "/").startsWith(target.getPath() + "/")) {
                throw new InvalidPayloadException(target.getName() + " cannot move into itself");
            }
            if (!ContentTypes.holds(parentNode, node.getPrimaryNodeType())) {
                throw new InvalidPayloadException(parent.getName() + " cannot hold " + target.getName());
            }
            checkScope(context, target, parent);
            final String before = Placement.before(context, parentNode);
            final String name = node.getParent().isSame(parentNode) ? target.getName()
                : NodeNameUtils.findFreeName(parent, target.getName());
            VersionableContent.checkOut(node.getParent());
            VersionableContent.checkOut(parentNode);
            context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE,
                this.mover.move(node, parentNode, name, before));
            Placement.number(parentNode, node.getPrimaryNodeType().getName(),
                context.getActivity().get(Placement.ORDER_PROPERTY, String.class));
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot move " + target.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Where the event moves the target.
     *
     * @param context the executing task's context
     * @param target the target
     * @return the parent the event names, or the target's own
     * @throws InvalidPayloadException when it names something that is not there
     */
    private static Resource parent(final WorkflowTaskContext context, final Resource target)
        throws InvalidPayloadException
    {
        final Object path = context.getEvent().get(PARENT_PARAMETER);
        if (path == null) {
            return target.getParent();
        }
        final Resource parent =
            path instanceof String ? context.getResourceResolver().getResource((String) path) : null;
        if (parent == null) {
            throw new InvalidPayloadException("There is nothing at " + path + " to move into");
        }
        return parent;
    }

    /**
     * Checks that the target stays inside the ancestor the activity keeps it within, if it names one.
     *
     * @param context the executing task's context
     * @param target the target
     * @param parent where it goes
     * @throws InvalidPayloadException when it would leave it
     */
    private static void checkScope(final WorkflowTaskContext context, final Resource target, final Resource parent)
        throws InvalidPayloadException
    {
        final String within = context.getActivity().get(WITHIN, String.class);
        if (within == null) {
            return;
        }
        final Optional<String> from = scopeOf(target.getParent(), within);
        if (from.isEmpty() || !from.equals(scopeOf(parent, within))) {
            throw new InvalidPayloadException(target.getName() + " can only move within its " + within);
        }
    }

    /**
     * Where the nearest resource of a type is, at or above one.
     *
     * @param resource where to start
     * @param type the resource type
     * @return its path, empty when there is none
     */
    private static Optional<String> scopeOf(final Resource resource, final String type)
    {
        return Stream.iterate(resource, Objects::nonNull, Resource::getParent)
            .filter(candidate -> candidate.isResourceType(type))
            .findFirst()
            .map(Resource::getPath);
    }
}
