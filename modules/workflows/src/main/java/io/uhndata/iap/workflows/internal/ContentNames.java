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
package io.uhndata.iap.workflows.internal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.jackrabbit.util.Text;

import io.uhndata.iap.utils.NodeNameUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;

/**
 * The names content may be given by the event of a task naming it: one a node can have, matching the
 * {@code namePattern} that applies when there is one, and not one a sibling has already, since a name asked for is
 * taken as asked or refused; and the name content takes after what it says, when it is given none. The frontend's
 * {@code suggestName} makes names the same way, so that what it suggests is what content would be called.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ContentNames
{
    /** The payload entry giving the name asked for. */
    static final String NAME_PARAMETER = "name";

    /** The property holding the pattern names must match. */
    static final String NAME_PATTERN = "namePattern";

    /** The property saying, in words, what names may be. */
    static final String NAME_HINT = "nameHint";

    /** The property of a listed type saying, when false, that it takes no name of its own. */
    static final String NAMED = "named";

    /** How many words of a text make a name: enough to recognize, short enough to read in a path. */
    private static final int NAME_WORDS = 5;

    private ContentNames()
    {
        // Utility class
    }

    /**
     * The name an event asks for, checked.
     *
     * @param given what the event gives as the name
     * @param pattern the pattern it must match, or {@code null}
     * @return the name, or {@code null} when the event asks for none
     * @throws InvalidPayloadException when it asks for one content cannot take
     */
    static String requested(final Object given, final String pattern) throws InvalidPayloadException
    {
        if (given == null || given instanceof String text && text.isBlank()) {
            return null;
        }
        if (!(given instanceof String name) || !allowed(name, pattern)) {
            throw new InvalidPayloadException(given + " is not a name this can take");
        }
        return name;
    }

    /**
     * Whether content may take a name: one segment of a path, with nothing JCR reserves, not a step up or in place,
     * and matching the pattern if there is one.
     *
     * @param name the name
     * @param pattern the pattern, or {@code null}
     * @return whether it may
     */
    static boolean allowed(final String name, final String pattern)
    {
        return Text.escapeIllegalJcrChars(name).equals(name) && !Set.of(".", "..").contains(name)
            && (pattern == null || name.matches(pattern));
    }

    /**
     * Refuses a name a child of a parent has already.
     *
     * @param parent the parent
     * @param name the name
     * @throws InvalidPayloadException when it is taken
     * @throws RepositoryException when the parent cannot be read
     */
    static void checkFree(final Node parent, final String name) throws InvalidPayloadException, RepositoryException
    {
        if (parent.hasNode(name)) {
            throw new InvalidPayloadException("There is already something called " + name + " here");
        }
    }

    /**
     * A name made of the first few words of a text, without accents, camel-cased: {@code "Âge à l'entrée"} gives
     * {@code ageALEntree}. Letters and digits of any script make words; everything else separates them.
     *
     * @param text any text
     * @return its first words, camel-cased, empty when it has none
     */
    static String fromText(final String text)
    {
        final String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return NodeNameUtils.camelCase(Arrays.stream(plain.split("[^\\p{L}\\p{N}]+"))
            .filter(word -> !word.isEmpty())
            .limit(NAME_WORDS)
            .collect(Collectors.joining(" ")));
    }
}
