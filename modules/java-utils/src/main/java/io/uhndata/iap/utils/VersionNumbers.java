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
package io.uhndata.iap.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Numbers the versions kept as children of one parent. A version's node is named after its number, as {@code v3},
 * and its label is {@code 3.0} unless its creator gives another.
 *
 * <p>The next number comes from the names, not the labels, since a label is free text and can be a year. It is the
 * largest number plus one, not the count plus one, so a version deleted from the middle does not free its number.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class VersionNumbers
{
    /** A leading whole number, after an optional {@code v}: {@code v3}, {@code 3.0}, {@code 3}. */
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile("^[vV]?(\\d{1,9})");

    private VersionNumbers()
    {
    }

    /**
     * The number of the next version under a parent: one past the largest number in a version's name there.
     *
     * @param parent the resource holding the versions
     * @param versionType the resource type of a version, which tells the versions from the parent's other children
     * @return a number no version under the parent is named with yet; {@code 1} for the first
     */
    public static int next(@NotNull final Resource parent, @NotNull final String versionType)
    {
        return StreamSupport.stream(parent.getChildren().spliterator(), false)
            .filter(child -> child.isResourceType(versionType))
            .mapToInt(version -> numberIn(version.getName()))
            .max()
            .orElse(0) + 1;
    }

    /**
     * The node name of a version: {@code v3}, unless something else is named so already.
     *
     * @param parent the resource the version is created under
     * @param number the version's number, from {@link #next}
     * @return {@code v} followed by the number, with a further suffix if that name is taken, as
     *     {@link NodeNameUtils#findFreeName} adds one
     */
    @NotNull
    public static String nodeName(@NotNull final Resource parent, final int number)
    {
        return NodeNameUtils.findFreeName(parent, "v" + number);
    }

    /**
     * The label of a version whose creator gives none, such as {@code 3.0}.
     *
     * @param number the version's number, from {@link #next}
     * @return the label
     */
    @NotNull
    public static String defaultLabel(final int number)
    {
        return number + ".0";
    }

    /**
     * The whole number a version's name starts with, after an optional {@code v}: 3 for {@code v3} or {@code 3.0}.
     *
     * @param name a version's node name
     * @return the number; 0 if the name starts with none
     */
    private static int numberIn(final String name)
    {
        final Matcher number = LEADING_NUMBER_PATTERN.matcher(name);
        return number.find() ? Integer.parseInt(number.group(1)) : 0;
    }
}
