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
package io.uhndata.iap.history.internal;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.api.RecordedEffect;
import io.uhndata.iap.utils.DateUtils;

/**
 * An action on its way from the code that describes it to the commit that records it. It travels as the JCR user
 * data of the committing session, which reaches every commit editor. User data that is not a description is ignored.
 *
 * @param name the name the action is filed under
 * @param cause the text the action carries about its cause, by the name of the property that stores it
 * @param occurredAt when the action happened, as a repository date; {@code null} if it happens as it is recorded
 * @param complete whether the action is complete as recorded, or waits for snapshots
 * @param effects the parts the code named for what it affected
 * @version $Id$
 * @since 0.1.0
 */
record ActionDescription(String name, Map<String, String> cause, String occurredAt, boolean complete,
    List<RecordedEffect> effects)
{
    /** The key that tells a history description from other user data. */
    private static final String DESCRIPTION_KEY = "iap:history";

    private static final String NAME_KEY = "name";

    private static final String CAUSE_KEY = "cause";

    private static final String OCCURRED_AT_KEY = "occurredAt";

    private static final String COMPLETE_KEY = "complete";

    private static final String EFFECTS_KEY = "effects";

    private static final String PATH_KEY = "path";

    private static final String ROLE_KEY = "role";

    /**
     * Constructor keeping its own copies.
     *
     * @param name the name the action is filed under
     * @param cause the text the action carries about its cause
     * @param occurredAt when the action happened, or {@code null} if it happens as it is recorded
     * @param complete whether the action is complete as recorded
     * @param effects the parts the code named
     */
    ActionDescription
    {
        cause = Map.copyOf(cause);
        effects = List.copyOf(effects);
    }

    /**
     * Describes an action.
     *
     * @param name the name to file it under
     * @param action the action
     * @return its description
     */
    static ActionDescription of(final String name, final RecordedAction action)
    {
        return new ActionDescription(name, CauseProperties.of(action), DateUtils.toString(action.getOccurredAt()),
            !action.isAwaitingSnapshots(), action.getEffects());
    }

    /**
     * The description as the session carries it.
     *
     * @return a JSON object holding the name, the cause, when it happened, whether it is complete, and the effects
     */
    String toUserData()
    {
        final JsonObjectBuilder description = Json.createObjectBuilder()
            .add(NAME_KEY, this.name)
            .add(CAUSE_KEY, Json.createObjectBuilder(this.cause))
            .add(COMPLETE_KEY, this.complete);
        if (this.occurredAt != null) {
            description.add(OCCURRED_AT_KEY, this.occurredAt);
        }
        final JsonArrayBuilder effectList = Json.createArrayBuilder();
        this.effects.forEach(effect -> effectList.add(
            Json.createObjectBuilder().add(PATH_KEY, effect.path()).add(ROLE_KEY, effect.role())));
        description.add(EFFECTS_KEY, effectList);
        return Json.createObjectBuilder().add(DESCRIPTION_KEY, description).build().toString();
    }

    /**
     * Reads a history description out of a commit's user data.
     *
     * @param userData the user data of the commit, as Oak hands it over
     * @return the description, or empty if the user data is absent or something else
     * @throws RuntimeException if the user data is a history description missing what every description holds
     */
    static Optional<ActionDescription> fromUserData(final Object userData)
    {
        if (!(userData instanceof final String text) || !text.contains(DESCRIPTION_KEY)) {
            return Optional.empty();
        }
        try {
            final JsonValue parsed = Json.createReader(new StringReader(text)).readValue();
            if (parsed.getValueType() != JsonValue.ValueType.OBJECT
                || !parsed.asJsonObject().containsKey(DESCRIPTION_KEY)) {
                return Optional.empty();
            }
            return Optional.of(read(parsed.asJsonObject().getJsonObject(DESCRIPTION_KEY)));
        } catch (final JsonException e) {
            // Somebody else's text that happens to name the key
            return Optional.empty();
        }
    }

    private static ActionDescription read(final JsonObject description)
    {
        final Map<String, String> cause = description.getJsonObject(CAUSE_KEY).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, property -> ((JsonString) property.getValue()).getString()));
        final List<RecordedEffect> effects = description.getJsonArray(EFFECTS_KEY).getValuesAs(JsonObject.class)
            .stream()
            .map(effect -> new RecordedEffect(effect.getString(PATH_KEY), effect.getString(ROLE_KEY)))
            .collect(Collectors.toList());
        return new ActionDescription(description.getString(NAME_KEY), cause,
            description.getString(OCCURRED_AT_KEY, null), description.getBoolean(COMPLETE_KEY), effects);
    }
}
