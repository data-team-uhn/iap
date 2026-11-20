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
package io.uhndata.iap.profiles.api;

import java.util.List;

import org.jetbrains.annotations.NotNull;

/**
 * One field of one account's profile: what is recorded, and what the person asking may do with it. Deliberately holds
 * nothing but immutable facts, and not the catalogue definition it was resolved from: the rights are resolved for a
 * particular requester and are not a property of the field, so two people reading the same profile see the same
 * definitions and different verdicts.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ProfileField
{
    /**
     * Where a value came from, as far as can be told without recording it. Deliberately not an audit trail: nothing
     * stores who last wrote a field, so "somebody set this" is as precise as this can honestly be.
     *
     * @since 0.1.0
     */
    public enum Provenance
    {
        /** Imported from the identity provider, and overwritten by it on every synchronisation. */
        IDP,
        /** Maintained by the platform. */
        PLATFORM,
        /** Recorded against the account by somebody using the application. */
        LOCAL,
        /** Nothing is recorded. */
        UNSET
    }

    private final String name;

    private final List<String> values;

    private final boolean readable;

    private final boolean editable;

    private final Provenance provenance;

    /**
     * Basic constructor.
     *
     * @param name the field name the catalogue knows
     * @param values what is recorded, empty when nothing is or when the requester may not see it
     * @param readable whether the requester may see the value
     * @param editable whether the requester may change it
     * @param provenance where the value came from
     */
    public ProfileField(@NotNull final String name, @NotNull final List<String> values, final boolean readable,
        final boolean editable, @NotNull final Provenance provenance)
    {
        this.name = name;
        this.values = List.copyOf(values);
        this.readable = readable;
        this.editable = editable;
        this.provenance = provenance;
    }

    /**
     * The field name the catalogue knows.
     *
     * @return a field name
     */
    @NotNull
    public String getName()
    {
        return this.name;
    }

    /**
     * What is recorded against the account.
     *
     * @return the values, empty when nothing is recorded or the requester may not see it
     */
    @NotNull
    public List<String> getValues()
    {
        return this.values;
    }

    /**
     * Whether the requester may see the value.
     *
     * @return {@code true} if the value is theirs to read
     */
    public boolean isReadable()
    {
        return this.readable;
    }

    /**
     * Whether the requester may change the value.
     *
     * @return {@code true} if the value is theirs to change
     */
    public boolean isEditable()
    {
        return this.editable;
    }

    /**
     * Where the value came from.
     *
     * @return the provenance
     */
    @NotNull
    public Provenance getProvenance()
    {
        return this.provenance;
    }
}
