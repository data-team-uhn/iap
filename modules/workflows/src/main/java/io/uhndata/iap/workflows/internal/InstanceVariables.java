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

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.workflows.models.Variable;

/**
 * Reads and writes a running instance's variables, so a gateway can route on what a service task just recorded
 * and a later delivery can read what an earlier one left.
 *
 * <p>Handlers work with an in-memory map. Conditions read {@code wf:Variable} children of the instance. Without
 * the write, a guard such as {@code verdict == 'PROPOSAL'} never holds, even when the handler set it; without the
 * read, a handler that runs after the instance waited for a person sees nothing the walk before the wait
 * recorded.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class InstanceVariables
{
    private static final String JCR_PRIMARY_TYPE = "jcr:primaryType";

    private static final String DATA_TYPE = "dataType";

    private static final String STRING_VALUE = "stringValue";

    private static final String LONG_VALUE = "longValue";

    private static final String DOUBLE_VALUE = "doubleValue";

    private static final String BOOLEAN_VALUE = "booleanValue";

    private static final String DATE_VALUE = "dateValue";

    private static final String REFERENCE_VALUE = "referenceValue";

    private static final List<String> VALUE_PROPERTIES = List.of(
        STRING_VALUE, LONG_VALUE, DOUBLE_VALUE, BOOLEAN_VALUE, DATE_VALUE, REFERENCE_VALUE);

    private InstanceVariables()
    {
    }

    /**
     * Brings what the instance already carries into a delivery's variables.
     *
     * <p>A name the delivery has already touched stands, whatever the instance says: what this walk recorded is
     * newer, and a name a handler cleared stays cleared rather than coming back from disk.</p>
     *
     * @param instance the running instance
     * @param variables the delivery's variables, added to
     */
    static void load(final Resource instance, final Map<String, Object> variables)
    {
        for (final Resource child : instance.getChildren()) {
            if (variables.containsKey(child.getName()) || !child.isResourceType(Variable.RESOURCE_TYPE)) {
                continue;
            }
            final Variable variable = child.adaptTo(Variable.class);
            if (variable != null) {
                variables.put(child.getName(), variable.getValue());
            }
        }
    }

    /**
     * Writes back the variables of a delivery that say something the instance does not say already.
     *
     * <p>Only what changed, rather than all of them after every activity. A reading carries the model's whole
     * reply as a variable, and rewriting tens of kilobytes once per step of the walk is work nobody asked
     * for.</p>
     *
     * @param instance the running instance
     * @param variables the delivery's variables, including {@code null} for a name that should be taken down
     * @throws PersistenceException when a variable cannot be written
     */
    static void flush(final Resource instance, final Map<String, Object> variables) throws PersistenceException
    {
        final Map<String, Object> stored = new LinkedHashMap<>();
        load(instance, stored);
        for (final Map.Entry<String, Object> entry : variables.entrySet()) {
            if (!stored.containsKey(entry.getKey())
                || !Objects.equals(stored.get(entry.getKey()), entry.getValue())) {
                persist(instance, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Writes one variable, or removes it when the value is {@code null}.
     *
     * @param instance the running instance
     * @param name the variable's name, which is the child node's name
     * @param value the value to store, or {@code null} to take an earlier one down
     * @throws PersistenceException when the variable cannot be written
     */
    static void persist(final Resource instance, final String name, final Object value) throws PersistenceException
    {
        if (name == null || name.isBlank()) {
            return;
        }
        final Resource existing = instance.getChild(name);
        if (value == null) {
            remove(existing);
            return;
        }
        final Map<String, Object> properties = propertiesOf(value);
        if (properties == null) {
            return;
        }
        if (existing == null) {
            instance.getResourceResolver().create(instance, name, properties);
            return;
        }
        if (existing.isResourceType(Variable.RESOURCE_TYPE)) {
            overwrite(existing, properties);
        }
    }

    /**
     * The properties a new {@code wf:Variable} node is created with for this value, or {@code null} when the
     * value is not a type the instance can carry.
     *
     * @param value the value a handler left behind
     * @return create properties, or {@code null} to skip
     */
    private static Map<String, Object> propertiesOf(final Object value)
    {
        String dataType = null;
        String property = null;
        Object stored = null;
        if (value instanceof String text) {
            dataType = Variable.TYPE_STRING;
            property = STRING_VALUE;
            stored = text;
        } else if (value instanceof Boolean flag) {
            dataType = Variable.TYPE_BOOLEAN;
            property = BOOLEAN_VALUE;
            stored = flag;
        } else if (value instanceof Double number) {
            dataType = Variable.TYPE_DOUBLE;
            property = DOUBLE_VALUE;
            stored = number;
        } else if (value instanceof Float number) {
            dataType = Variable.TYPE_DOUBLE;
            property = DOUBLE_VALUE;
            stored = number.doubleValue();
        } else if (value instanceof Calendar when) {
            dataType = Variable.TYPE_DATE;
            property = DATE_VALUE;
            stored = when;
        } else if (value instanceof Number number) {
            dataType = Variable.TYPE_LONG;
            property = LONG_VALUE;
            stored = number.longValue();
        }
        return stored == null ? null : typed(dataType, property, stored);
    }

    /**
     * A create-map for one typed value.
     *
     * @param dataType the declared {@link Variable} type
     * @param property the typed property that holds the value
     * @param value the value
     * @return properties including the node type
     */
    private static Map<String, Object> typed(final String dataType, final String property, final Object value)
    {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JCR_PRIMARY_TYPE, "wf:Variable");
        properties.put(DATA_TYPE, dataType);
        properties.put(property, value);
        return properties;
    }

    /**
     * Replaces the stored type and value on an existing variable, clearing any other typed property so a later
     * read cannot pick up a leftover from a previous type.
     *
     * @param existing the variable node
     * @param properties the type and value to keep
     */
    private static void overwrite(final Resource existing, final Map<String, Object> properties)
    {
        final ModifiableValueMap stored = Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class),
            "A node the engine is writing is always modifiable");
        stored.put(DATA_TYPE, properties.get(DATA_TYPE));
        for (final String property : VALUE_PROPERTIES) {
            if (properties.containsKey(property)) {
                stored.put(property, properties.get(property));
            } else {
                stored.remove(property);
            }
        }
    }

    /**
     * Removes a variable that is no longer set. Leaves anything else of that name alone: tokens and tasks live
     * alongside variables and must not be deleted because a handler reused their name.
     *
     * @param existing the child of that name, or {@code null}
     * @throws PersistenceException when the node cannot be deleted
     */
    private static void remove(final Resource existing) throws PersistenceException
    {
        if (existing != null && existing.isResourceType(Variable.RESOURCE_TYPE)) {
            existing.getResourceResolver().delete(existing);
        }
    }
}
