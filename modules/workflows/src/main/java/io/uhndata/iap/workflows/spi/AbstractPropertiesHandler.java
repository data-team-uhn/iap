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
package io.uhndata.iap.workflows.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * The base of a service task writing properties onto the resource an event was aimed at, limited to the ones its
 * activity allows. A subclass says which those are, what each may hold, and what the request asks for.
 *
 * <p>The whole request is checked before anything is written, so a refusal leaves the resource as it was. A property
 * that arrives empty is removed, unless it is mandatory, and one the request does not name is left as it stands, so
 * a client may send only what it is changing.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class AbstractPropertiesHandler implements ServiceTaskHandler
{
    /**
     * One property a request may change.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public interface EditableProperty
    {
        /**
         * The property's name.
         *
         * @return a property name
         */
        @NotNull
        String name();

        /**
         * Whether the property may not be removed or left empty.
         *
         * @return {@code true} for a property the resource cannot do without
         */
        boolean mandatory();

        /**
         * What a reference must point to.
         *
         * @return the resource type a referenced resource must have, or {@code null} for a property holding text
         */
        @Nullable
        String referenceType();
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final List<String> allowed = allowed(context);
        if (allowed.isEmpty()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not list the properties a request may change");
        }
        final Map<EditableProperty, Object> changes = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : requested(context).entrySet()) {
            if (allowed.contains(entry.getKey())) {
                final EditableProperty property = property(context, entry.getKey());
                changes.put(property, value(context, property, entry.getValue()));
            } else if (refusesUnlisted()) {
                throw new InvalidPayloadException(entry.getKey() + " cannot be edited here");
            }
        }
        final Resource target = context.getTarget();
        VersioningUtils.checkOut(target);
        for (final Map.Entry<EditableProperty, Object> change : changes.entrySet()) {
            write(target, change.getKey(), change.getValue());
        }
    }

    /**
     * The properties the activity lets a request change. Asked first, so it is also where a subclass refuses a
     * target it does not serve.
     *
     * @param context the executing task's context
     * @return property names, empty when the activity lists none, which is a mistake in the definition
     * @throws WorkflowException when the target is not one this handler serves
     */
    @NotNull
    protected abstract List<String> allowed(@NotNull WorkflowTaskContext context) throws WorkflowException;

    /**
     * What one property the activity allows may hold.
     *
     * @param context the executing task's context
     * @param name a property the activity allows
     * @return the property
     * @throws WorkflowException when the activity allows a property the target cannot have
     */
    @NotNull
    protected abstract EditableProperty property(@NotNull WorkflowTaskContext context, @NotNull String name)
        throws WorkflowException;

    /**
     * What the request asks for, by property name: text to store, or {@code null} to remove the property. Anything
     * else is refused.
     *
     * @param context the executing task's context
     * @return the requested values, in the order they are checked
     * @throws WorkflowException when the request cannot be read
     */
    @NotNull
    protected abstract Map<String, Object> requested(@NotNull WorkflowTaskContext context) throws WorkflowException;

    /**
     * Whether a request naming a property the activity does not allow is refused, rather than that entry ignored.
     *
     * @return {@code true}, unless a subclass's requests carry entries that are not properties
     */
    protected boolean refusesUnlisted()
    {
        return true;
    }

    /**
     * Checks one requested value.
     *
     * @param context the executing task's context
     * @param property the property being set
     * @param requested the requested value
     * @return the text or the referenced resource to store, or {@code null} to remove the property
     * @throws InvalidPayloadException when the value does not suit the property
     */
    private static Object value(final WorkflowTaskContext context, final EditableProperty property,
        final Object requested) throws InvalidPayloadException
    {
        if (requested != null && !(requested instanceof String)) {
            throw new InvalidPayloadException(property.name() + " must be a string");
        }
        final String text = requested == null ? "" : ((String) requested).trim();
        if (text.isEmpty()) {
            if (property.mandatory()) {
                throw new InvalidPayloadException(property.name() + " cannot be empty");
            }
            return null;
        }
        if (property.referenceType() == null) {
            return text;
        }
        final Resource referenced = context.getResourceResolver().getResource(text);
        if (referenced == null || !referenced.isResourceType(property.referenceType())) {
            throw new InvalidPayloadException("There is nothing " + property.name() + " can point to at " + text);
        }
        return referenced;
    }

    /**
     * Writes one checked value.
     *
     * @param target the resource being edited
     * @param property the property being set
     * @param value what {@link #value} made of the request
     * @throws PersistenceException when the resource cannot be modified
     */
    private static void write(final Resource target, final EditableProperty property, final Object value)
        throws PersistenceException
    {
        if (value instanceof Resource) {
            ReferenceUtils.setReference(target, property.name(), (Resource) value);
            return;
        }
        final ModifiableValueMap values = target.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The resource " + target.getPath() + " cannot be modified");
        }
        if (value == null) {
            values.remove(property.name());
        } else {
            values.put(property.name(), value);
        }
    }
}
