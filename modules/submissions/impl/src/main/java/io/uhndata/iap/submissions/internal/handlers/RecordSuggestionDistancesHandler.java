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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Service task {@code recordSuggestionDistances}: measures how far each answer the submitter sends is from the
 * suggestion a reading made for it, and writes that on the suggestion's run. A workflow places it right after the
 * task the submitter completes to send the request, so each answer is measured once, as it was sent.
 *
 * <p>Zero means the submitter took the suggestion as it was; more means they edited or replaced it, so no separate
 * flag is needed. Only the run the form showed last is measured ({@link Answer#getShownExtraction}), and only once:
 * sending the request again keeps the first measure. An answer left empty, or a run that found nothing, measures
 * nothing. A suggestion equal to what the submitter had typed before the reading was not shown either, but nothing
 * at send time tells it apart from one the submitter took, so it counts as taken.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class RecordSuggestionDistancesHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "recordSuggestionDistances";

    private static final String EDIT_DISTANCE_PROPERTY = "editDistance";

    private static final String PERCENTAGE_DISTANCE_PROPERTY = "percentageDistance";

    private static final double PERCENT_SCALE = 100.0;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = SubmissionHost.require(ExecutionHost.of(context), HANDLER_NAME);
        VersioningUtils.checkOut(target);
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "A submission adapts to its model");
        for (final Answer answer : submission.getAnswers()) {
            record(target, answer);
        }
    }

    /** Measures one answer against the suggestion the form showed for it, unless there is nothing to measure. */
    private static void record(final Resource target, final Answer answer) throws PersistenceException
    {
        final Extraction shown = answer.getShownExtraction();
        final String[] values = answer.getValue();
        if (!isUnmeasuredSuggestion(shown) || values == null || values.length == 0) {
            return;
        }
        // In order, so taking the suggested values in another order still counts as taking them as they were
        final String suggested = joinSorted(shown.getExtractedValues().stream());
        final int distance = LevenshteinDistance.getDefaultInstance().apply(suggested, joinSorted(Stream.of(values)));
        final Resource run = target.getResourceResolver().getResource(shown.getPath());
        final ModifiableValueMap properties = run == null ? null : run.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the distance on " + shown.getPath());
        }
        properties.put(EDIT_DISTANCE_PROPERTY, (long) distance);
        // Of the suggestion's own length; an empty suggestion counts as one character, not a division by zero
        properties.put(PERCENTAGE_DISTANCE_PROPERTY, PERCENT_SCALE * distance / Math.max(1, suggested.length()));
    }

    /** Whether the run suggested something and nobody measured an answer against it yet. */
    private static boolean isUnmeasuredSuggestion(final Extraction run)
    {
        return run != null && !run.isActedOn() && !run.getExtractedValues().isEmpty();
    }

    private static String joinSorted(final Stream<String> values)
    {
        return values.sorted().collect(Collectors.joining(", "));
    }
}
