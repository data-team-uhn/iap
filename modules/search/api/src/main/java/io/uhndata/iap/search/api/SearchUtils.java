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
     * The key of the match description in a serialized result, beside the node's own properties. No node stores it.
     */
    public static final String MATCH_KEY = "data:queryMatch";

    /** How many characters of context to keep on either side of the matched text. */
    private static final int MAX_CONTEXT = 8;

    /** What replaces the context that was cut off. */
    private static final String ELLIPSIS_MARKER = "...";

    private static final String LABEL_KEY = "label";

    private static final String PATH_KEY = "@path";

    private static final String BEFORE_KEY = "before";

    private static final String TEXT_KEY = "text";

    private static final String AFTER_KEY = "after";

    private SearchUtils()
    {
        // Utility class
    }

    /**
     * Escapes the characters that have a special meaning in the pattern of a {@code like} condition, so that the
     * pattern matches the input itself. The result is a pattern, not a string literal. Writing it into a statement
     * still needs {@link #escapeQueryArgument}.
     *
     * @param input the text to escape
     * @return the input, with a backslash before each backslash, {@code %} and {@code _}
     */
    @NotNull
    public static String escapeLikeText(@NotNull final String input)
    {
        return input.replaceAll("([\\\\%_])", "\\\\$1");
    }

    /**
     * Escapes the input for use inside a single-quoted string literal in a query. Doubling the quote is the only
     * escape such a literal has. A backslash is an ordinary character in one.
     *
     * <p>
     * Only an engine that writes a value into its statement needs this. Prefer binding the value, which keeps it out
     * of the statement. A bound pattern still needs {@link #escapeLikeText}, since the pattern grammar applies to
     * whatever the variable holds.
     * </p>
     *
     * @param input the text to escape
     * @return the input, with each single quote doubled
     */
    @NotNull
    public static String escapeQueryArgument(@NotNull final String input)
    {
        return input.replace("'", "''");
    }

    /**
     * Finds which of a property's values contains the searched text. It accepts whatever a value map returns for a
     * property: one value or an array, of any type.
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
        // Any array, not only an Object[]. A value map returns a long[] or a boolean[] for a multi-valued property of
        // those types, and an instanceof Object[] test would match the query against "[J@6b88"
        if (value.getClass().isArray()) {
            final String[] values = new String[Array.getLength(value)];
            for (int i = 0; i < values.length; ++i) {
                // A null element reads as the empty string, or a search for "nul" would match it
                values[i] = Objects.toString(Array.get(value, i), "");
            }
            return getMatchFromArray(values, query);
        }
        return Strings.CI.contains(value.toString(), query) ? value.toString() : null;
    }

    /**
     * Finds which of a list of strings contains the searched text.
     *
     * @param values the strings to search through; {@code null} for none
     * @param query the text to look for, matched case-insensitively
     * @return the first string containing the query; {@code null} if none does
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
     * @param label a name for what matched, shown to the user, such as the label of the field holding the value;
     *            {@code null} for none, written as an empty string
     * @param path the path of the node holding the matched value; {@code null} for none, written as an empty string
     * @return a copy of the result with the match description added; the result itself if the value does not
     *         contain the query
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
     * @param label a name for what matched, shown to the user
     * @param path the path of the node holding the matched value
     * @return the description; {@code null} if the value does not contain the query
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

        // From the stored value, not the query: the match ignores case, so the two can differ
        builder.add(TEXT_KEY, matchedValue.substring(matchIndex, matchIndex + query.length()));

        final String after = matchedValue.substring(matchIndex + query.length());
        builder.add(AFTER_KEY, trimmed(after, true));

        return builder.build();
    }

    /**
     * Cuts a piece of context down to {@value #MAX_CONTEXT} characters, and marks with {@value #ELLIPSIS_MARKER} that
     * there was more. It counts code points, not {@code char}s, so that it never cuts an emoji in half.
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
            return context.substring(0, context.offsetByCodePoints(0, MAX_CONTEXT)) + ELLIPSIS_MARKER;
        }
        return ELLIPSIS_MARKER + context.substring(context.offsetByCodePoints(context.length(), -MAX_CONTEXT));
    }
}
