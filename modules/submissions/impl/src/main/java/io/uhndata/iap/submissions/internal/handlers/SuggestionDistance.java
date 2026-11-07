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

import java.util.Objects;

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
     * Measures an answer against the newest run's suggestion. Does nothing when there is no run, the run found
     * nothing, or the run was already measured.
     *
     * @param answer the answer just saved
     * @param values what it now holds; clearing a field is not answering it, so an empty answer measures nothing
     */
    static void record(final Resource answer, final String[] values)
    {
        final Answer model = values.length == 0 ? null : answer.adaptTo(Answer.class);
        final Extraction shown = model == null ? null : model.getLatestExtraction();
        if (shown == null || shown.isActedOn() || shown.getExtractedAnswer() == null) {
            return;
        }
        // Compare values, not the raw reply: the suggestion is split and matched to options the way answers are saved
        final String suggested = String.join(", ", model.getSuggestedValues());
        final int distance = LevenshteinDistance.getDefaultInstance().apply(suggested, String.join(", ", values));
        final Resource run = Objects.requireNonNull(answer.getResourceResolver().getResource(shown.getPath()),
            "A run the answer just reported is still where it said");
        final ModifiableValueMap properties = Objects.requireNonNull(run.adaptTo(ModifiableValueMap.class),
            "The engine writes through a session that can modify what it was given");
        properties.put(EDIT_DISTANCE_PROPERTY, (long) distance);
        // Of the suggestion's own length; an empty suggestion counts as one character, not a division by zero
        properties.put(PERCENTAGE_DISTANCE_PROPERTY, PERCENT_SCALE * distance / Math.max(1, suggested.length()));
    }
}
