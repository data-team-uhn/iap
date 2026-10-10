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

import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The built-in service task creating content inside the target: a child of the event's {@code type}, which must be
 * one the activity lists and the target's type accepts (see {@link ContentTypes}), placed ahead of the sibling the
 * event names as {@code before}, or else last. It takes the {@code name} the event asks for, when it asks, which
 * must be free and one the activity allows (see {@link ContentNames}); otherwise it is named after the first of the
 * fields the activity names in {@code nameFrom} that the event's {@code patch} gives, when that makes a name the
 * activity allows, or else after its type. It is empty: an {@code updateContent} task that follows fills it in from
 * the same patch, since it acts on what was created. A type with an {@code orderProperty} has the new content and its
 * siblings of its type numbered by their places in it (see {@link Placement}).
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CreateContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "createContent";

    /** The payload entry naming the node type to create. */
    static final String TYPE_PARAMETER = "type";

    /** The activity property naming, in order, the fields a name is taken from. */
    static final String NAME_FROM = "nameFrom";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final List<ContentTypes.Type> listed = ContentTypes.listedBy(context.getActivity());
        if (listed.isEmpty()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " must list the " + ContentTypes.TYPES + " it may create");
        }
        final Resource target = context.getTarget();
        final Node parent = Nodes.of(target);
        try {
            final ContentTypes.Type type = chosen(context, ContentTypes.accepted(listed, parent));
            final String before = Placement.before(context, parent);
            final String name = name(context, parent, type);
            VersioningUtils.checkOut(parent);
            final Node created = parent.addNode(name, type.nodeType());
            if (before != null) {
                parent.orderBefore(name, before);
            }
            Placement.number(parent, type.nodeType(), type.orderProperty());
            context.setVariable(WorkflowResult.CREATED_PATH_VARIABLE, created.getPath());
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot create content in " + target.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The type the event asks for, among those that may be created.
     *
     * @param context the executing task's context
     * @param accepted the types that may be created in the target
     * @return the type
     * @throws InvalidPayloadException when the event names none, or one that may not be created here
     */
    private static ContentTypes.Type chosen(final WorkflowTaskContext context, final List<ContentTypes.Type> accepted)
        throws InvalidPayloadException
    {
        final Object type = context.getEvent().get(TYPE_PARAMETER);
        if (!(type instanceof String)) {
            throw new InvalidPayloadException("A type is required");
        }
        return accepted.stream()
            .filter(candidate -> candidate.nodeType().equals(type))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(type + " cannot be created here"));
    }

    /**
     * What to name new content: the name the event asks for, if it asks, which must be free; or else a free one after
     * what it says, when that is a name the activity allows, or after its type.
     *
     * @param context the executing task's context
     * @param parent where it is created
     * @param type the type created
     * @return a free name
     * @throws InvalidPayloadException when the name asked for cannot be taken, or the patch is not a JSON object
     * @throws RepositoryException when the parent cannot be read
     */
    private static String name(final WorkflowTaskContext context, final Node parent, final ContentTypes.Type type)
        throws InvalidPayloadException, RepositoryException
    {
        final Object given = context.getEvent().get(ContentNames.NAME_PARAMETER);
        if (!type.named() && given != null) {
            throw new InvalidPayloadException(type.label() + " takes no name of its own");
        }
        final String requested = ContentNames.requested(given, type.namePattern());
        if (requested != null) {
            ContentNames.checkFree(parent, requested);
            return requested;
        }
        final String derived = derivedName(context, type);
        return NodeNameUtils.findFreeName(context.getTarget(),
            ContentNames.allowed(derived, type.namePattern()) ? derived : type.defaultName());
    }

    /**
     * The name new content would take after what it says: the first words of the first field the activity names it
     * from that the patch gives, or else its type.
     *
     * @param context the executing task's context
     * @param type the type created
     * @return a name, maybe already taken
     * @throws InvalidPayloadException when the patch is not a JSON object
     */
    private static String derivedName(final WorkflowTaskContext context, final ContentTypes.Type type)
        throws InvalidPayloadException
    {
        final String[] nameFrom = context.getActivity().get(NAME_FROM, String[].class);
        if (nameFrom == null || context.getEvent().get(UpdateContentHandler.PATCH_PARAMETER) == null) {
            return type.defaultName();
        }
        final JsonObject patch = UpdateContentHandler.patch(context);
        for (final String field : nameFrom) {
            final String name =
                patch.get(field) instanceof JsonString ? ContentNames.fromText(patch.getString(field)) : "";
            if (!name.isEmpty()) {
                return name;
            }
        }
        return type.defaultName();
    }
}
