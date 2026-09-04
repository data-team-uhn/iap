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
 * Derives node names from human-given titles, the way the shipped content is named: camel-cased, and dodging
 * name collisions with a numeric suffix. Deliberately free of any policy about what a bad title <em>means</em> —
 * an unusable title simply yields an empty name, for the caller to turn into its own kind of refusal.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NodeNameUtils
{
    /** How many identically-named siblings get a readable counter before the names stop being worth reading. */
    private static final int MAX_DUPLICATES = 100;

    /** The low end of the random suffix used past that, chosen so every one of them has the same width. */
    private static final int RANDOM_SUFFIX_MIN = 100_000_000;

    /** One past the high end of the random suffix. */
    private static final int RANDOM_SUFFIX_MAX = 1_000_000_000;

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
     * <p>
     * Past {@value #MAX_DUPLICATES} siblings the counter stops being worth reading, so the suffix becomes a
     * random number instead — retried until it lands on a free name. There is deliberately no failing case: every
     * caller wants a name it may create a child under, and the only alternative to an ugly name is refusing to
     * record something that did happen.
     * </p>
     *
     * @param parent the resource the new child would be created under
     * @param base the natural name, e.g. derived by {@link #camelCase}
     * @return a free name
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
