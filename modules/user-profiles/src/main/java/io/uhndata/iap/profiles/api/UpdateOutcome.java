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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;

/**
 * What came of an attempt to change a profile. Either everything asked for was written, or nothing was: a request that
 * names a field nobody may change, or a value of the wrong shape, is refused whole, because a half-saved profile is a
 * worse thing to hand back than a rejected one.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class UpdateOutcome
{
    /** The one refusal that is about the account rather than about a field. */
    private static final String NOT_YOURS = "";

    private final List<String> changed;

    private final SortedMap<String, String> refused;

    private final boolean forbidden;

    /**
     * Basic constructor.
     *
     * @param changed the fields whose value is now different, in the order they were written
     * @param refused the fields that were not written, each with the reason
     */
    public UpdateOutcome(@NotNull final List<String> changed, @NotNull final Map<String, String> refused)
    {
        this(changed, refused, false);
    }

    private UpdateOutcome(@NotNull final List<String> changed, @NotNull final Map<String, String> refused,
        final boolean forbidden)
    {
        this.changed = List.copyOf(changed);
        this.refused = new TreeMap<>(refused);
        this.forbidden = forbidden;
    }

    /**
     * The refusal for somebody who has no business changing this profile at all, as opposed to one who asked for
     * something a particular field does not allow. Worth telling apart, because only this one is about the person
     * asking rather than about what they asked for.
     *
     * @param reason why not, worded for whoever is looking at the form
     * @return an outcome in which nothing was written
     */
    @NotNull
    public static UpdateOutcome forbidden(@NotNull final String reason)
    {
        return new UpdateOutcome(List.of(), Map.of(NOT_YOURS, reason), true);
    }

    /**
     * Whether the refusal is about the person asking rather than about what they asked for.
     *
     * @return {@code true} when they may not change this profile at all
     */
    public boolean isForbidden()
    {
        return this.forbidden;
    }

    /**
     * The fields whose value is now different. A field asked to keep the value it already had is not listed: nothing
     * was written for it.
     *
     * @return field names
     */
    @NotNull
    public List<String> getChanged()
    {
        return this.changed;
    }

    /**
     * The fields that were not written, each with the reason, worded for whoever is looking at the form.
     *
     * @return field names mapped to reasons, empty when the request was carried out
     */
    @NotNull
    public Map<String, String> getRefused()
    {
        return Collections.unmodifiableMap(this.refused);
    }

    /**
     * Whether anything was refused, in which case nothing at all was written.
     *
     * @return {@code true} if the request was turned down
     */
    public boolean isRefused()
    {
        return !this.refused.isEmpty();
    }

    /**
     * Serializes the outcome, keyed by field name so that a form can put each reason against the right control.
     *
     * @return a JSON object
     */
    @NotNull
    public JsonObject toJson()
    {
        final JsonArrayBuilder written = Json.createArrayBuilder();
        this.changed.forEach(written::add);
        final JsonObjectBuilder reasons = Json.createObjectBuilder();
        this.refused.forEach(reasons::add);
        return Json.createObjectBuilder()
            .add("status", isRefused() ? "error" : "success")
            // Said explicitly, because the one refusal that is about the person rather than about a field is keyed by
            // no field name at all, and a form putting each reason against its own control would drop it silently
            .add("forbidden", this.forbidden)
            .add("changed", written)
            .add("refused", reasons)
            .build();
    }
}
