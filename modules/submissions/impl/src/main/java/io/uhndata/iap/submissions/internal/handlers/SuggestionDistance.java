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
 * How far a submitter's answer ended up from the suggestion they were shown, written on that suggestion's run the
 * first time they answer. A distance of zero is a suggestion taken as it stood, anything higher one edited or
 * replaced, so what the submitter did with it needs no flag of its own. A run measured once keeps that measure.
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
     * Measure an answer against the suggestion it was given, if it was given one nobody has acted on yet.
     *
     * @param answer the answer just saved
     * @param values what it now holds; clearing a field is not answering it, so an empty answer measures nothing
     */
    static void record(final Resource answer, final String[] values)
    {
        final Answer model = values.length == 0 ? null : answer.adaptTo(Answer.class);
        final Extraction shown = model == null ? null : model.getSurestExtraction();
        if (shown == null || shown.isActedOn() || shown.getExtractedAnswer() == null) {
            return;
        }
        // Compared as the form shows them: the suggestion split and matched the way the answer is stored
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
