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
package io.uhndata.iap.schemas.editing.internal;

import java.util.List;
import java.util.function.Function;

/**
 * How messages name a part of a schema version, so every message calls a part the same way. A part is named by what
 * people read: the first of its text, label and title that is not blank, in quotes. Without one, it is named by its
 * path within the version, such as {@code form/age}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PartNames
{
    /** The properties that name a part, in the order they are tried. */
    static final List<String> NAMED_BY = List.of("text", "label", "title");

    private PartNames()
    {
        // Utility class
    }

    /**
     * How a message names a part.
     *
     * @param propertyOf reads one of the part's properties by name, returning {@code null} if the part does not have it
     * @param pathInVersion the part's path relative to its version, such as {@code form/age}
     * @return the first name that is not blank, in quotes; else the path
     */
    public static String of(final Function<String, String> propertyOf, final String pathInVersion)
    {
        return NAMED_BY.stream()
            .map(propertyOf)
            .filter(name -> name != null && !name.isBlank())
            .findFirst()
            .map(name -> "\"" + name + "\"")
            .orElse(pathInVersion);
    }

    /**
     * A path relative to one of its ancestors.
     *
     * @param path the absolute path of a node
     * @param ancestorPath the absolute path of one of its ancestors
     * @return the path below the ancestor, without a leading {@code /}
     */
    public static String pathIn(final String path, final String ancestorPath)
    {
        return path.substring(ancestorPath.length() + 1);
    }
}
