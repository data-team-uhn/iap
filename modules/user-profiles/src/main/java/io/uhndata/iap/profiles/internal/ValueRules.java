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
package io.uhndata.iap.profiles.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.profiles.models.ProfileFieldDefinition;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.DataType;

/**
 * Decides whether what somebody typed can be recorded in a field, and says why not in words that belong next to the
 * control they typed it into.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ValueRules
{
    private ValueRules()
    {
        // Utility class
    }

    /**
     * The values actually stated, since a form posts an empty string for a control somebody cleared, and that means
     * "record nothing" rather than "record an empty string".
     *
     * @param asked the values as they arrived
     * @return the values worth recording, trimmed, in order
     */
    @NotNull
    static List<String> stated(@Nullable final String[] asked)
    {
        return asked == null ? List.of()
            : Arrays.stream(asked).filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    /**
     * Why a set of values cannot be recorded in a field, if it cannot.
     *
     * @param definition what the catalogue says
     * @param asked the new values
     * @return the reason, or {@code null} when they are acceptable
     */
    @Nullable
    static String rejects(@NotNull final ProfileFieldDefinition definition, @Nullable final String[] asked)
    {
        final List<String> wanted = stated(asked);
        if (!definition.isMultiple() && wanted.size() > 1) {
            return "only one value is accepted here";
        }
        if (definition.isRequired() && wanted.isEmpty()) {
            return "a value is required";
        }
        for (final String value : wanted) {
            final String problem = rejectsValue(definition, value);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * Why one value cannot be recorded in a field, if it cannot.
     *
     * @param definition what the catalogue says
     * @param value the value to check
     * @return the reason, or {@code null} when it is acceptable
     */
    @Nullable
    private static String rejectsValue(@NotNull final ProfileFieldDefinition definition, @NotNull final String value)
    {
        final List<String> allowed = definition.getAllowedValues();
        if (!allowed.isEmpty() && !allowed.contains(value)) {
            return "must be one of: " + String.join(", ", allowed);
        }
        final String expected = definition.getPattern();
        if (expected != null && !expected.isBlank() && !Pattern.matches(expected, value)) {
            return "is not in the expected format";
        }
        return wrongType(definition.getDataType(), value);
    }

    /**
     * Whether a value can be read as the type a field expects.
     *
     * @param type the expected type
     * @param value the value to check
     * @return the reason it cannot, or {@code null} when it can
     */
    @Nullable
    private static String wrongType(@Nullable final DataType type, @NotNull final String value)
    {
        try {
            if (type == DataType.LONG) {
                Long.parseLong(value);
            } else if (type == DataType.DOUBLE) {
                Double.parseDouble(value);
            } else if (type == DataType.DATE) {
                LocalDate.parse(value);
            } else if (type == DataType.BOOLEAN && !"true".equals(value) && !"false".equals(value)) {
                return "must be true or false";
            }
        } catch (final NumberFormatException | DateTimeParseException e) {
            return "must be a " + type.name().toLowerCase(Locale.ROOT)
                + (type == DataType.DATE ? ", written as YYYY-MM-DD" : "");
        }
        return null;
    }
}
