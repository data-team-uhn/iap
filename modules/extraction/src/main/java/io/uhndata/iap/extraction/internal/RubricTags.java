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
package io.uhndata.iap.extraction.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The rubric vocabulary a chunk can be tagged with: B.1 to B.17 of the ICH-GCP protocol contents, which
 * {@code protocol_structure.md} sets out in full and {@code protocol_structure_glossary.md} in one line each.
 *
 * <p>One list, used twice over. It is the enum handed to the model in a response schema, so a provider that
 * honours the schema cannot answer off-list, and it is the filter every reply is read through, because a
 * provider that ignores the schema still can. Two lists would drift, and the one that drifted would be the
 * one nothing tested.
 *
 * <p>Nothing here caps how many tags a chunk may carry. A chunk really can be background and objectives at
 * once, and the model is closer to the text than a cap would be.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RubricTags
{
    /** Every tag a chunk may carry, in rubric order. */
    static final List<String> ALL = List.of("B.1", "B.2", "B.3", "B.4", "B.5", "B.6", "B.7", "B.8", "B.9",
        "B.10", "B.11", "B.12", "B.13", "B.14", "B.15", "B.16", "B.17");

    private RubricTags()
    {
        // Constants and two helpers
    }

    /**
     * Whether a tag is one of the rubrics.
     *
     * @param tag what the model called it, which may be surrounded by whitespace or nothing at all
     * @return {@code true} when it names a rubric
     */
    static boolean isValid(final String tag)
    {
        return tag != null && ALL.contains(tag.strip());
    }

    /**
     * The rubrics among what a model offered, in the order it offered them, each at most once.
     *
     * @param tags what the model answered, which may hold anything or nothing
     * @return the tags worth writing down, empty when none of them was a rubric
     */
    static List<String> filter(final List<String> tags)
    {
        if (tags == null) {
            return List.of();
        }
        final List<String> valid = new ArrayList<>(tags.size());
        for (final String tag : tags) {
            if (isValid(tag) && !valid.contains(tag.strip())) {
                valid.add(tag.strip());
            }
        }
        return List.copyOf(valid);
    }
}
