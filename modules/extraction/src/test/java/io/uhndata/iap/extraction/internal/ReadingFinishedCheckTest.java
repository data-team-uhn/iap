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

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReadingFinishedCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReadingFinishedCheckTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION_PATH = "/Submissions/aRequest";

    private final SlingContext context = new SlingContext();

    private final ReadingFinishedCheck check = new ReadingFinishedCheck();

    private Resource createSubmission(final String status)
    {
        return this.context.create().resource(SUBMISSION_PATH,
            Map.of(TYPE, Submission.RESOURCE_TYPE, ExtractionStatus.STATUS_PROPERTY, status));
    }

    @Test
    void appliesToASubmission()
    {
        assertTrue(this.check.appliesTo(createSubmission(ExtractionStatus.STATUS_DONE)));
    }

    @Test
    void doesNotApplyToAnythingElse()
    {
        assertFalse(this.check.appliesTo(this.context.create().resource("/Schemas/study",
            Map.of(TYPE, "sch/Schema"))));
    }

    @Test
    void refusesARequestWhoseDocumentIsStillRead()
    {
        assertEquals(List.of(ReadingFinishedCheck.STILL_READING_MESSAGE),
            this.check.check(createSubmission(ExtractionStatus.STATUS_RUNNING)));
    }

    @Test
    void letsARequestGoOnceTheReadingEnded()
    {
        assertEquals(List.of(), this.check.check(createSubmission(ExtractionStatus.STATUS_DONE)));
    }

    // A stopped or failed reading leaves nothing behind to write onto the request
    @Test
    void letsARequestGoAfterAReadingFailed()
    {
        assertEquals(List.of(), this.check.check(createSubmission(ExtractionStatus.STATUS_FAILED)));
    }

    @Test
    void letsARequestNeverReadGo()
    {
        assertEquals(List.of(), this.check.check(this.context.create().resource(SUBMISSION_PATH,
            Map.of(TYPE, Submission.RESOURCE_TYPE))));
    }
}
