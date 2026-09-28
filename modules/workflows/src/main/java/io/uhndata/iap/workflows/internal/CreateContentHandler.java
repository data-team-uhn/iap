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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
 * event names as {@code before}, or else last. It is named after the first of the fields the activity names in
 * {@code nameFrom} that the event's {@code patch} gives, or else after its type, and it is empty: an
 * {@code updateContent} task that follows fills it in from the same patch, since it acts on what was created.
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

    /** The payload entry naming the sibling the new content goes before. */
    static final String BEFORE_PARAMETER = "before";

    /** The activity property naming, in order, the fields a name is taken from. */
    static final String NAME_FROM = "nameFrom";

    /** How many words of a field make a name: enough to recognize, short enough to read in a path. */
    private static final int NAME_WORDS = 5;

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
        final Node parent = Objects.requireNonNull(target.adaptTo(Node.class), "Content is stored in a JCR repository");
        try {
            final ContentTypes.Type type = chosen(context, ContentTypes.accepted(listed, parent));
            final String before = before(context, parent);
            final String name = NodeNameUtils.findFreeName(target, name(context, type));
            VersioningUtils.checkOut(parent);
            final Node created = parent.addNode(name, type.nodeType());
            if (before != null) {
                parent.orderBefore(name, before);
            }
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
     * The sibling the event places new content before, if it names one.
     *
     * @param context the executing task's context
     * @param parent the node new content is created under
     * @return the sibling's name, or {@code null} to place it last
     * @throws InvalidPayloadException when that is not a child of the parent, or the parent keeps no order
     * @throws RepositoryException when the parent cannot be read
     */
    private static String before(final WorkflowTaskContext context, final Node parent)
        throws InvalidPayloadException, RepositoryException
    {
        final Object before = context.getEvent().get(BEFORE_PARAMETER);
        if (before == null) {
            return null;
        }
        if (!(before instanceof String) || !parent.hasNode((String) before)) {
            throw new InvalidPayloadException("There is nothing called " + before + " to go before");
        }
        if (!parent.getPrimaryNodeType().hasOrderableChildNodes()) {
            throw new InvalidPayloadException(parent.getName() + " keeps no order to place new content in");
        }
        return (String) before;
    }

    /**
     * What to name new content: the first words of the first field the activity names it from that the patch
     * gives, or else its type.
     *
     * @param context the executing task's context
     * @param type the type created
     * @return a name, maybe already taken
     * @throws InvalidPayloadException when the patch is not a JSON object
     */
    private static String name(final WorkflowTaskContext context, final ContentTypes.Type type)
        throws InvalidPayloadException
    {
        final String[] nameFrom = context.getActivity().get(NAME_FROM, String[].class);
        if (nameFrom == null || context.getEvent().get(UpdateContentHandler.PATCH_PARAMETER) == null) {
            return type.defaultName();
        }
        final JsonObject patch = UpdateContentHandler.patch(context);
        for (final String field : nameFrom) {
            final String name = patch.get(field) instanceof JsonString ? firstWords(patch.getString(field)) : "";
            if (!name.isEmpty()) {
                return name;
            }
        }
        return type.defaultName();
    }

    /**
     * A name made of the first few words of a text.
     *
     * @param text any text
     * @return its first words, camel-cased, empty when it has none
     */
    private static String firstWords(final String text)
    {
        return NodeNameUtils.camelCase(Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
            .filter(word -> !word.isEmpty())
            .limit(NAME_WORDS)
            .collect(Collectors.joining(" ")));
    }
}
