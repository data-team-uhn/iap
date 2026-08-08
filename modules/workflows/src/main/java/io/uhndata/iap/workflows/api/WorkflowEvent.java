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

import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Something that happened, expressed in the domain's terms: a creation was requested, a task was decided, a
 * deadline passed. Events are what drives every workflow — nothing changes state except in response to one.
 *
 * <p>An event is deliberately channel-blind. Whether it arrived as an HTTP request, an inbound email, or a firing
 * timer is the business of the translator that built it; by the time the {@link WorkflowEngine engine} sees it,
 * only the {@link #getName() name} — matched against the message names of waiting catching events — and the
 * {@link #getPayload() payload} remain.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowEvent
{
    private final String name;

    private final Map<String, Object> payload;

    /**
     * Constructor.
     *
     * @param name the domain event name, e.g. {@code create}
     * @param payload the data carried by the event; copied, and must not contain {@code null} keys or values
     */
    public WorkflowEvent(@NotNull final String name, @NotNull final Map<String, Object> payload)
    {
        this.name = name;
        this.payload = Map.copyOf(payload);
    }

    /**
     * The domain event name, matched against the {@code messageName} of the catching events workflows are waiting
     * on.
     *
     * @return an event name
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * The data carried by the event, e.g. the submitted form fields of the HTTP request it was translated from.
     *
     * @return an unmodifiable map, possibly empty
     */
    @NotNull
    public Map<String, Object> getPayload()
    {
        return this.payload;
    }

    /**
     * A single payload value.
     *
     * @param key the payload key
     * @return the value, or {@code null} if the payload does not carry it
     */
    @Nullable
    public Object get(@NotNull final String key)
    {
        return this.payload.get(key);
    }
}
