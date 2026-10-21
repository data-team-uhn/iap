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
package io.uhndata.iap.documents.generation.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How a {@link DocumentGenerator#generate generation} ended. Each status says whose problem a failure is, which
 * decides what the caller does with it: show it to whoever fixes the template, retry later, or tell an
 * administrator.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class GenerationResult
{
    /**
     * How the generation ended.
     *
     * @since 0.1.0
     */
    public enum Status
    {
        /** Both documents were produced. */
        GENERATED,
        /**
         * The template cannot be filled: it is not a {@code .docx}, it uses a value nobody provides, or it has a tag
         * in the wrong place. Retrying will not help; fixing the template will.
         */
        TEMPLATE_ERROR,
        /** The worker could not take the request: it is busy, down, or did not answer in time. Retrying may help. */
        UNAVAILABLE,
        /** Anything else, such as a misconfigured token or a value no template can hold. An administrator is needed. */
        FAILED
    }

    private final Status status;

    private final GeneratedDocument document;

    private final String message;

    private GenerationResult(final Status status, final GeneratedDocument document, final String message)
    {
        this.status = status;
        this.document = document;
        this.message = message;
    }

    /**
     * Record a generation that worked.
     *
     * @param document what was produced
     * @return a {@link Status#GENERATED} result
     */
    @NotNull
    public static GenerationResult generated(@NotNull final GeneratedDocument document)
    {
        return new GenerationResult(Status.GENERATED, document, "");
    }

    /**
     * Record a generation that did not work.
     *
     * @param status why, anything but {@link Status#GENERATED}
     * @param message what went wrong, in terms its reader can act on
     * @return a result with no document
     */
    @NotNull
    public static GenerationResult failed(@NotNull final Status status, @NotNull final String message)
    {
        if (status == Status.GENERATED) {
            throw new IllegalArgumentException("A generated result needs its document");
        }
        return new GenerationResult(status, null, message);
    }

    /**
     * How the generation ended.
     *
     * @return a status
     */
    @NotNull
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * What was produced.
     *
     * @return the documents, {@code null} unless the status is {@link Status#GENERATED}
     */
    @Nullable
    public GeneratedDocument getDocument()
    {
        return this.document;
    }

    /**
     * What went wrong.
     *
     * @return a message, empty when the status is {@link Status#GENERATED}
     */
    @NotNull
    public String getMessage()
    {
        return this.message;
    }

    @Override
    public String toString()
    {
        return this.message.isEmpty() ? this.status.name() : this.status + ": " + this.message;
    }
}
