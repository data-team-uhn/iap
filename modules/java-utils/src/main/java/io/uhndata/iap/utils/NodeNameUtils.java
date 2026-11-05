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
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Derives node names from titles people give: camel-cased, with a numeric suffix where the name is taken. A title
 * with no letters or digits yields an empty name, and the caller decides what that means.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeNameUtils
{
    /** How many names {@link #findFreeName} tries, counting the natural one, before it draws random suffixes. */
    private static final int MAX_DUPLICATES = 100;

    /** The smallest random suffix. Every random suffix has nine digits. */
    private static final int RANDOM_SUFFIX_MIN = 100_000_000;

    /** One past the largest random suffix. */
    private static final int RANDOM_SUFFIX_MAX = 1_000_000_000;

    private NodeNameUtils()
    {
    }

    /**
     * Turns a title into a node name made only of letters and digits: {@code "My cool workflow"} becomes
     * {@code myCoolWorkflow}. Letters outside ASCII are kept, so {@code "Café menu"} becomes {@code caféMenu}.
     *
     * @param title the title to derive a name from
     * @return the camel-cased name; empty if the title has no letters or digits
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
     * is: {@code myCoolWorkflow}, {@code myCoolWorkflow2}, {@code myCoolWorkflow3}, and so on.
     *
     * <p>
     * Once the natural name and its counted variants up to {@value #MAX_DUPLICATES} are all taken, the suffix is a
     * random nine-digit number. It is drawn again until the name is free, so a name is always found.
     * </p>
     *
     * @param parent the resource the new child would be created under
     * @param base the natural name, such as one {@link #camelCase} derives
     * @return a name that no child of the parent has, as the parent's resolver sees it
     */
    @NotNull
    public static String findFreeName(@NotNull final Resource parent, @NotNull final String base)
    {
        return IntStream.rangeClosed(1, MAX_DUPLICATES)
            .mapToObj(attempt -> attempt == 1 ? base : base + attempt)
            .filter(candidate -> parent.getChild(candidate) == null)
            .findFirst()
            .orElseGet(() -> randomName(parent, base));
    }

    /**
     * A name with a random suffix, drawn until one of them is free.
     *
     * @param parent the resource the new child would be created under
     * @param base the natural name the suffix is appended to
     * @return a free name
     */
    private static String randomName(final Resource parent, final String base)
    {
        return Stream.generate(
            () -> base + ThreadLocalRandom.current().nextInt(RANDOM_SUFFIX_MIN, RANDOM_SUFFIX_MAX))
            .filter(candidate -> parent.getChild(candidate) == null)
            .findFirst()
            .orElseThrow();
    }
}
