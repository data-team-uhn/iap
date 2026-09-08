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
package io.uhndata.iap.search.api;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers for writing a {@link io.uhndata.iap.search.spi.QuickSearchEngine quick search engine}. They escape the
 * user's input for a query, find which of a property's values matched, and describe that match for the client to
 * highlight.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class SearchUtils
{
    /**
     * The property holding the match description on a returned result. Not a repository property. It is added to the
     * serialized result, alongside the node's own properties.
     */
    public static final String MATCH_KEY = "data:queryMatch";

    /** How many characters of context to include on either side of the matched text. */
    private static final int MAX_CONTEXT = 8;

    /** The marker standing in for the context that was cut off. */
    private static final String ELLIPSIS = "...";

    private static final String LABEL_KEY = "label";

    private static final String PATH_KEY = "@path";

    private static final String BEFORE_KEY = "before";

    private static final String TEXT_KEY = "text";

    private static final String AFTER_KEY = "after";

    private SearchUtils()
    {
        // Utility class, never instantiated
    }

    /**
     * Escapes the characters that have a special meaning in the pattern of a {@code like} condition, leaving input
     * that matches only itself. The result is a pattern, not a string literal. Placing it in a query still needs
     * {@link #escapeQueryArgument}.
     *
     * @param input the text to escape
     * @return an escaped version of the input
     */
    @NotNull
    public static String escapeLikeText(@NotNull final String input)
    {
        return input.replaceAll("([\\\\%_])", "\\\\$1");
    }

    /**
     * Escapes the input for use inside a single-quoted string literal in a query.
     *
     * @param input the text to escape
     * @return an escaped version of the input
     */
    @NotNull
    public static String escapeQueryArgument(@NotNull final String input)
    {
        return input.replace("'", "''");
    }

    /**
     * Finds which of a property's values contains the searched text. A property holds one value or several, of any
     * type, and this accepts whatever a value map returns for it.
     *
     * @param value the raw property value, a single value or an array, of any type
     * @param query the text to look for, matched case-insensitively
     * @return the value containing the query, the first one if the property has several; {@code null} if none does
     */
    @Nullable
    public static String getMatch(@Nullable final Object value, @NotNull final String query)
    {
        if (value == null) {
            return null;
        }
        // Any array, not only an Object[]: a value map hands back a long[] or a boolean[] for a multi-valued
        // property of those types. An instanceof Object[] test misses them and matches the query against "[J@6b88"
        if (value.getClass().isArray()) {
            final String[] values = new String[Array.getLength(value)];
            for (int i = 0; i < values.length; ++i) {
                // A null element stands in as the empty string. The default rendering of "null" would make a
                // search for "nul" match it
                values[i] = Objects.toString(Array.get(value, i), "");
            }
            return getMatchFromArray(values, query);
        }
        return Strings.CI.contains(value.toString(), query) ? value.toString() : null;
    }

    /**
     * Finds which of a list of strings contains the searched text.
     *
     * @param values the strings to search through
     * @param query the text to look for, matched case-insensitively
     * @return the first string containing the query, or {@code null} if none does
     */
    @Nullable
    public static String getMatchFromArray(@Nullable final String[] values, @NotNull final String query)
    {
        if (values == null) {
            return null;
        }
        return Arrays.stream(values).filter(value -> Strings.CI.contains(value, query)).findFirst().orElse(null);
    }

    /**
     * Adds a description of the match to an already serialized result, under {@value #MATCH_KEY}.
     *
     * @param result the serialized result to describe
     * @param matchedValue the value that matched, as returned by {@link #getMatch}
     * @param query the text that was searched for
     * @param label a human-readable name for what matched, e.g. the label of the field holding the value
     * @param path the path of the node holding the matched value
     * @return a copy of the result with the match description added, or the result itself when there is nothing to
     *         describe
     */
    @NotNull
    public static JsonObject addMatchMetadata(@NotNull final JsonObject result, @NotNull final String matchedValue,
        @NotNull final String query, @Nullable final String label, @Nullable final String path)
    {
        final JsonObject match = getMatchMetadata(matchedValue, query, label, path);
        if (match == null) {
            return result;
        }
        final JsonObjectBuilder builder = Json.createObjectBuilder(result);
        builder.add(MATCH_KEY, match);
        return builder.build();
    }

    /**
     * Describes a match: what matched, and enough of the text around it for the client to show the match in context.
     *
     * @param matchedValue the value that matched
     * @param query the text that was searched for
     * @param label a human-readable name for what matched
     * @param path the path of the node holding the matched value
     * @return the description, or {@code null} if the value does not actually contain the query
     */
    @Nullable
    private static JsonObject getMatchMetadata(final String matchedValue, final String query, final String label,
        final String path)
    {
        final int matchIndex = Strings.CI.indexOf(matchedValue, query);
        if (matchIndex < 0) {
            return null;
        }
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add(LABEL_KEY, label == null ? "" : label);
        builder.add(PATH_KEY, path == null ? "" : path);

        final String before = matchedValue.substring(0, matchIndex);
        builder.add(BEFORE_KEY, trimmed(before, false));

        // Taken from the stored value, not from the query: the match is case-insensitive and the two can differ
        builder.add(TEXT_KEY, matchedValue.substring(matchIndex, matchIndex + query.length()));

        final String after = matchedValue.substring(matchIndex + query.length());
        builder.add(AFTER_KEY, trimmed(after, true));

        return builder.build();
    }

    /**
     * Cuts a piece of context down to {@value #MAX_CONTEXT} characters, marking with {@value #ELLIPSIS} that there
     * was more. The count is in code points, not in {@code char}s. Cutting an emoji in half would leave the response
     * holding an unpaired surrogate.
     *
     * @param context the text to cut down
     * @param fromStart {@code true} to keep the beginning of the text, {@code false} to keep the end
     * @return the trimmed context
     */
    private static String trimmed(final String context, final boolean fromStart)
    {
        if (context.codePointCount(0, context.length()) <= MAX_CONTEXT) {
            return context;
        }
        if (fromStart) {
            return context.substring(0, context.offsetByCodePoints(0, MAX_CONTEXT)) + ELLIPSIS;
        }
        return ELLIPSIS + context.substring(context.offsetByCodePoints(context.length(), -MAX_CONTEXT));
    }
}
