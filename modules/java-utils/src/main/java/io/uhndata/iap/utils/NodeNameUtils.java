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

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Derives node names from human-given titles, the way the shipped content is named: camel-cased, and dodging
 * name collisions with a numeric suffix. Deliberately free of any policy about what a bad title <em>means</em> —
 * an unusable title simply yields an empty name, and an exhausted namespace yields no name, for the caller to
 * turn into its own kind of refusal.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeNameUtils
{
    /** How many identically-named siblings are tolerated before giving up on finding a free name. */
    private static final int MAX_DUPLICATES = 100;

    private NodeNameUtils()
    {
    }

    /**
     * Turns a human-given title into a JCR- and URL-friendly node name: {@code "My cool workflow"} becomes
     * {@code myCoolWorkflow}.
     *
     * @param title the title to derive a name from
     * @return a camel-cased name, empty when nothing usable remains — e.g. the title holds only punctuation
     */
    @NotNull
    public static String camelCase(@NotNull final String title)
    {
        final String[] words = Arrays.stream(title.split("[^\\p{L}\\p{N}]+"))
            .filter(word -> !word.isEmpty())
            .toArray(String[]::new);
        return IntStream.range(0, words.length)
            .mapToObj(i -> {
                final String word = words[i].toLowerCase(Locale.ROOT);
                return i == 0 ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
            })
            .collect(Collectors.joining());
    }

    /**
     * Finds a child name not yet taken under the given parent, appending a counter when the natural name already
     * is: {@code myCoolWorkflow}, {@code myCoolWorkflow2}, {@code myCoolWorkflow3}...
     *
     * @param parent the resource the new child would be created under
     * @param base the natural name, e.g. derived by {@link #camelCase}
     * @return a free name, or {@code null} when even {@value #MAX_DUPLICATES} variants are already taken
     */
    @Nullable
    public static String findFreeName(@NotNull final Resource parent, @NotNull final String base)
    {
        return IntStream.rangeClosed(1, MAX_DUPLICATES)
            .mapToObj(attempt -> attempt == 1 ? base : base + attempt)
            .filter(candidate -> parent.getChild(candidate) == null)
            .findFirst()
            .orElse(null);
    }
}
