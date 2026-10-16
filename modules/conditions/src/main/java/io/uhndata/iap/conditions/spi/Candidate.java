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
package io.uhndata.iap.conditions.spi;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.api.OperandType;

/**
 * Something an operand of a source can name, as the condition builder offers it: a question its answer is read
 * from, for instance. It says what the operand then holds, so the builder offers the comparisons and values that
 * fit.
 *
 * @param value what the operand stores to name it
 * @param label what the builder shows for it
 * @param type the type of the values the operand then holds, {@code null} if unknown
 * @param multiple whether the operand then holds several values, {@code null} if unknown
 * @param choices the values it can be compared with, when they are a known list; empty otherwise
 * @param aliases other names an operand may give it, e.g. a question's path in its version; possibly none
 * @param detail what tells it apart from another called the same, e.g. a question's identifier; {@code null} if
 *            nothing needs to
 * @version $Id$
 * @since 0.1.0
 */
public record Candidate(@NotNull String value, @NotNull String label, @Nullable OperandType type,
    @Nullable Boolean multiple, @NotNull List<Choice> choices, @NotNull List<String> aliases, @Nullable String detail)
{
    /**
     * Checks what has to be there and keeps a copy of the lists.
     */
    public Candidate
    {
        Objects.requireNonNull(value, "a candidate has to say what it stores");
        Objects.requireNonNull(label, "a candidate has to say what it is called");
        choices = choices == null ? List.of() : List.copyOf(choices);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /**
     * A candidate known by one name only, with nothing to tell it apart.
     *
     * @param value what the operand stores to name it
     * @param label what the builder shows for it
     * @param type the type of the values the operand then holds, {@code null} if unknown
     * @param multiple whether the operand then holds several values, {@code null} if unknown
     * @param choices the values it can be compared with, when they are a known list; empty otherwise
     */
    public Candidate(@NotNull final String value, @NotNull final String label, @Nullable final OperandType type,
        @Nullable final Boolean multiple, @NotNull final List<Choice> choices)
    {
        this(value, label, type, multiple, choices, List.of(), null);
    }

    /**
     * The candidate as the builder reads it, leaving out what is unknown.
     *
     * @return a JSON object
     */
    @NotNull
    public JsonObject toJson()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder().add("value", this.value).add("label", this.label);
        if (this.type != null) {
            json.add("type", this.type.name().toLowerCase(Locale.ROOT));
        }
        if (this.multiple != null) {
            json.add("multiple", this.multiple);
        }
        if (!this.choices.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            this.choices.forEach(choice -> array.add(Json.createObjectBuilder()
                .add("value", choice.value()).add("label", choice.label())));
            json.add("choices", array);
        }
        if (!this.aliases.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            this.aliases.forEach(array::add);
            json.add("aliases", array);
        }
        if (this.detail != null) {
            json.add("detail", this.detail);
        }
        return json.build();
    }

    /**
     * One value an operand can be compared with, and what the builder shows for it.
     *
     * @param value the value
     * @param label what the builder shows for it
     * @version $Id$
     * @since 0.1.0
     */
    public record Choice(@NotNull String value, @NotNull String label)
    {
    }
}
