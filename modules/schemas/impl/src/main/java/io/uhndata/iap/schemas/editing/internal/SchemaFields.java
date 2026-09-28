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
package io.uhndata.iap.schemas.editing.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.models.Activity;

/**
 * The fields a patch may set on each kind of schema content, and how each is stored. Which of them a given
 * workflow lets through is that workflow's own {@code fields} configuration.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SchemaFields
{
    /**
     * How a field's value is stored.
     *
     * @version $Id$
     * @since 0.1.0
     */
    enum Kind
    {
        /** A string. */
        TEXT,
        /** A REFERENCE to another node, given as its path. */
        REFERENCE
    }

    /**
     * One editable field.
     *
     * @param name the property name
     * @param label what the field is called where it is edited
     * @param kind how its value is stored
     * @param mandatory whether it may not be removed or left blank
     * @param multiline whether its text runs over several lines
     * @param referenceType for a reference, the resource type the referenced node must have
     * @version $Id$
     * @since 0.1.0
     */
    record Field(String name, String label, Kind kind, boolean mandatory, boolean multiline, String referenceType)
    {
    }

    /** The activity property listing the fields a patch may change. */
    static final String FIELDS_PARAMETER = "fields";

    private static final Map<String, List<Field>> BY_TYPE = Map.of(
        Schema.RESOURCE_TYPE, List.of(
            new Field("title", "Title", Kind.TEXT, true, false, null)),
        SchemaVersion.RESOURCE_TYPE, List.of(
            new Field("version", "Label", Kind.TEXT, true, false, null),
            new Field("description", "Description", Kind.TEXT, false, true, null),
            new Field("workflow", "Workflow", Kind.REFERENCE, false, false, "wf/WorkflowVersion")));

    private SchemaFields()
    {
        // Utility class
    }

    /**
     * Looks up a field of a kind of content.
     *
     * @param resourceType the content's resource type
     * @param name the field name
     * @return the field, empty when the content has no such editable field
     */
    @NotNull
    static Optional<Field> find(@NotNull final String resourceType, @NotNull final String name)
    {
        return BY_TYPE.getOrDefault(resourceType, List.of()).stream()
            .filter(field -> field.name().equals(name))
            .findFirst();
    }

    /**
     * The fields an {@code updateSchemaContent} activity lets a patch change, from its {@code fields}
     * configuration.
     *
     * @param activity the activity
     * @return the field names, empty when it configures none
     */
    @NotNull
    static List<String> allowedBy(@NotNull final Activity activity)
    {
        final Object fields = activity.get(FIELDS_PARAMETER);
        if (fields instanceof String[]) {
            return List.of((String[]) fields);
        }
        return fields instanceof String && !((String) fields).isBlank() ? List.of((String) fields) : List.of();
    }
}
