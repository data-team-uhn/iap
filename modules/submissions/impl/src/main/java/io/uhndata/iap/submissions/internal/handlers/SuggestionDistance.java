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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;

/**
 * Measures how far a submitter's answer is from the suggestion they were shown, and writes it on that suggestion's
 * run the first time they answer. Zero means they took the suggestion as it was; more means they edited or
 * replaced it, so no separate flag is needed. A run is measured only once.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SuggestionDistance
{
    private static final String EDIT_DISTANCE_PROPERTY = "editDistance";

    private static final String PERCENTAGE_DISTANCE_PROPERTY = "percentageDistance";

    private static final double PERCENT_SCALE = 100.0;

    private SuggestionDistance()
    {
        // Utility
    }

    /**
     * The suggestion the form offers next to an answer, read before a save changes the answer. See
     * {@link Answer#getOfferedSuggestion()}.
     *
     * @param answer the answer about to be saved
     * @return the offered values, empty when nothing was offered
     */
    static List<String> getOffered(final Resource answer)
    {
        return Objects.requireNonNull(answer.adaptTo(Answer.class), "An answer the submission reported is an answer")
            .getOfferedSuggestion();
    }

    /**
     * Measures an answer against the suggestion the submitter was offered, on the newest run. Does nothing when
     * nothing was offered, or the run was already measured. A run kept out of sight, because it repeats the answer
     * or the run before it, is never measured.
     *
     * @param answer the answer just saved
     * @param values what it now holds; clearing a field is not answering it, so an empty answer measures nothing
     * @param offered what the form offered before the save, from {@link #getOffered}
     */
    static void record(final Resource answer, final String[] values, final List<String> offered)
    {
        if (values.length == 0 || offered.isEmpty()) {
            return;
        }
        // Something was offered, so the answer has a newest run, the one that offered it
        final Extraction shown = Objects.requireNonNull(Objects.requireNonNull(answer.adaptTo(Answer.class),
            "An answer the submission reported is an answer").getLatestExtraction(), "An offer comes from a run");
        if (shown.isActedOn()) {
            return;
        }
        // In order, so taking the suggested values in another order still counts as taking them as they were
        final String suggested = joinSorted(offered.stream());
        final int distance = LevenshteinDistance.getDefaultInstance().apply(suggested, joinSorted(Stream.of(values)));
        final Resource run = Objects.requireNonNull(answer.getResourceResolver().getResource(shown.getPath()),
            "A run the answer just reported is still where it said");
        final ModifiableValueMap properties = Objects.requireNonNull(run.adaptTo(ModifiableValueMap.class),
            "The engine writes through a session that can modify what it was given");
        properties.put(EDIT_DISTANCE_PROPERTY, (long) distance);
        // Of the suggestion's own length; an empty suggestion counts as one character, not a division by zero
        properties.put(PERCENTAGE_DISTANCE_PROPERTY, PERCENT_SCALE * distance / Math.max(1, suggested.length()));
    }

    private static String joinSorted(final Stream<String> values)
    {
        return values.sorted().collect(Collectors.joining(", "));
    }
}
