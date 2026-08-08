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
package io.uhndata.iap.workflows.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What an accepted event amounted to: the workflow ran (or advanced) and these are the variables it left behind,
 * e.g. the path of an entity a bootstrap workflow created. The translator that fed the event in reads them to
 * shape its channel's answer — a redirect, a reply email, nothing at all.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowResult
{
    /** The variable under which a workflow reports the entity it created. */
    public static final String CREATED_PATH = "createdPath";

    private final Map<String, Object> variables;

    /**
     * Constructor.
     *
     * @param variables the variables left behind by the execution; copied
     */
    public WorkflowResult(@NotNull final Map<String, Object> variables)
    {
        // Not Map.copyOf, since workflow variables, unlike an event's payload, may legitimately hold null values
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /**
     * The variables left behind by the execution.
     *
     * @return an unmodifiable map, possibly empty
     */
    @NotNull
    public Map<String, Object> getVariables()
    {
        return this.variables;
    }

    /**
     * A single variable of the finished execution.
     *
     * @param name the variable name
     * @return the value, or {@code null} if the execution did not set it
     */
    @Nullable
    public Object getVariable(@NotNull final String name)
    {
        return this.variables.get(name);
    }
}
