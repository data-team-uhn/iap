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
 * Numbering versions, the same way whatever is being versioned: the next version is numbered one past the largest
 * number a version beside it is named with, named {@code v3} after that number, and labelled {@code 3.0} unless
 * whoever created it asked for another label.
 *
 * <p>Names are read rather than labels, since a label is free text, as likely a year as a number; and the largest
 * number is taken rather than the count, so that a version discarded from the middle leaves no number for a new one
 * to take again.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class VersionNumbers
{
    /** A leading whole number, after an optional {@code v}: {@code v3}, {@code 3.0}, {@code 3}. */
    private static final Pattern NUMBERED = Pattern.compile("^[vV]?(\\d{1,9})");

    private VersionNumbers()
    {
    }

    /**
     * The number of the next version under a parent: one past the largest a version there is named with.
     *
     * @param parent the resource holding the versions
     * @param versionType the resource type of a version, telling the versions from whatever else the parent holds
     * @return a number no version under the parent is named with yet, {@code 1} for the first
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
     * @return a free name, as {@link NodeNameUtils#findFreeName} finds one
     */
    @NotNull
    public static String nodeName(@NotNull final Resource parent, final int number)
    {
        return NodeNameUtils.findFreeName(parent, "v" + number);
    }

    /**
     * The label of a version nobody asked another label for: {@code 3.0}.
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
     * The whole number a version's name starts with: {@code v3} as {@link #nodeName} names them, or {@code 3.0} as
     * content imported by hand may be.
     *
     * @param name a version's node name
     * @return the number, or 0 when there is none
     */
    private static int numberIn(final String name)
    {
        final Matcher number = NUMBERED.matcher(name);
        return number.find() ? Integer.parseInt(number.group(1)) : 0;
    }
}
