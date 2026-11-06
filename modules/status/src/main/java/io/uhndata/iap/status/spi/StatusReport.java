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
package io.uhndata.iap.status.spi;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A report on one part of the state of the instance: a name, a level and a text.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class StatusReport
{
    /**
     * How much a report needs attention, from the least to the most. A reader asks for the reports at one level or
     * above.
     *
     * @since 0.1.0
     */
    public enum Status
    {
        /** Detail for somebody investigating, left out unless a reader asks for this level. */
        DEBUG,
        /** Information, with nothing to act on. */
        INFO,
        /** Something that was checked, and works. */
        SUCCESS,
        /** Something somebody should look at. */
        WARNING,
        /** A failure somebody must look at. */
        ERROR
    }

    private final String name;

    private final Status status;

    private final String text;

    /**
     * Creates a report.
     *
     * @param name the title of the report, such as {@code System Started}
     * @param status how much the report needs attention
     * @param text the body of the report; {@code null} if the name and the level say everything
     */
    public StatusReport(@NotNull final String name, @NotNull final Status status, @Nullable final String text)
    {
        this.name = name;
        this.status = status;
        this.text = text;
    }

    /**
     * The title of this report.
     *
     * @return a short title, such as {@code System Started}
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * How much this report needs attention.
     *
     * @return the level of this report
     */
    @NotNull
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * The body of this report.
     *
     * @return the text; {@code null} if the name and the level say everything
     */
    @Nullable
    public String getText()
    {
        return this.text;
    }

    /**
     * A short summary of this report, without its text.
     *
     * @return the name and the level, such as {@code Health Check: WARNING}
     */
    @Override
    @NotNull
    public String toString()
    {
        return getName() + ": " + getStatus();
    }

    /**
     * This report as JSON.
     *
     * @return an object with the keys {@code name}, {@code status} and {@code text}; {@code text} is empty if the
     *         report has no text
     */
    @NotNull
    public JsonObject toJson()
    {
        return Json.createObjectBuilder()
            .add("name", getName())
            .add("status", getStatus().toString())
            .add("text", getText() == null ? "" : getText())
            .build();
    }
}
