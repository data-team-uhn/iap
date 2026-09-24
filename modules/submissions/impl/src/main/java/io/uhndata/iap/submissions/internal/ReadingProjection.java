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
package io.uhndata.iap.submissions.internal;

import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Submission;

/**
 * What the form projection says about reading answers out of the attached documents: which form requirements the
 * model fills in, and how far the reading got.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ReadingProjection
{
    /** Where the extraction workflows record how far reading the documents got, and what they made of them. */
    private static final String EXTRACTION_STATUS = "extractionStatus";

    private static final String EXTRACTION_MESSAGE = "extractionMessage";

    /** Set once a job has taken the reading, which is after every parse has been read in. */
    private static final String READING_CLAIMED = "extractionReadingClaimed";

    private ReadingProjection()
    {
        // Static helper
    }

    /**
     * Whether a form requirement is filled in from a document, as opposed to answered by hand. The editor keeps
     * the two on different pages: the hand-filled page is where reading is started.
     *
     * <p>A classification's model prompt sits on the requirement itself, not on its decision question, so it is
     * extracted by definition. Any other form is extracted when one of its questions, including one nested in a
     * section, says how to ask a model.</p>
     *
     * @param requirement the form requirement
     * @return {@code true} when the model is asked to answer something here
     */
    static boolean isExtracted(final FormRequirement requirement)
    {
        return requirement instanceof ClassificationRequirement || isExtracted(requirement.getChildren());
    }

    /**
     * Where reading the answers out of the attached documents got to, once it has started: the state the view
     * shows a progress bar or a banner for, the message that goes with it, and whether asking again would do
     * anything. {@code parsed} and {@code reading} are the two moments the view can actually see: the daemon
     * has answered, and a job has taken the reading. Written by the extraction system workflows, read back here
     * by name.
     *
     * @param submission the submission being read
     * @return the extraction block, or {@code null} when no reading was ever asked for
     */
    static JsonObjectBuilder describe(final Submission submission)
    {
        final String status = submission.get(EXTRACTION_STATUS, String.class);
        if (status == null) {
            return null;
        }
        final JsonObjectBuilder json = Json.createObjectBuilder().add("status", status);
        final String message = submission.get(EXTRACTION_MESSAGE, String.class);
        if (message != null) {
            json.add("message", message);
        }
        json.add("retryable", ParseStatus.hasFailed(submission));
        json.add("parsed", ParseStatus.isSettled(submission));
        json.add("reading", Boolean.TRUE.equals(submission.get(READING_CLAIMED, Boolean.class)));
        return json;
    }

    private static boolean isExtracted(final List<FormItem> children)
    {
        for (final FormItem child : children) {
            if (child instanceof Question) {
                final String prompt = ((Question) child).getExtractionPrompt();
                if (prompt != null && !prompt.isBlank()) {
                    return true;
                }
            } else if (child instanceof Section && isExtracted(((Section) child).getChildren())) {
                return true;
            }
        }
        return false;
    }
}
