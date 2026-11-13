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

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ExtractionStatus}: saving the reading's status, message, block and claim on a
 * submission, and checking the submission out.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractionStatusTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void recordsTheStateAndItsMessage() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of());

        ExtractionStatus.recordStatus(submission, ExtractionStatus.STATUS_FAILED,
            "The model's answer could not be read");

        assertEquals("failed", submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("The model's answer could not be read",
            submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    // The same documents would get the same answer, so a block is for good
    @Test
    void keepsABlockWhateverStatusIsRecorded() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of());
        ExtractionStatus.markBlocked(submission);

        ExtractionStatus.recordStatus(submission, ExtractionStatus.STATUS_DONE, null);
        ExtractionStatus.recordStatus(submission, ExtractionStatus.STATUS_RUNNING, null);

        assertEquals(Boolean.TRUE, submission.getValueMap().get(ExtractionStatus.BLOCKED_PROPERTY, Boolean.class));
    }

    @Test
    void clearsAnEarlierMessageWhenThereIsNoneToRecord() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1",
            Map.of(ExtractionStatus.MESSAGE_PROPERTY, "something went wrong"));

        ExtractionStatus.recordStatus(submission, ExtractionStatus.STATUS_DONE, null);

        assertEquals("done", submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNull(submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void takesTheWholeClaimDown() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.READING_CLAIMED_PROPERTY, true, ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-1"));

        ExtractionStatus.releaseClaim(submission);

        assertNull(submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class));
        assertNull(submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesToReleaseAClaimItCannotWrite()
    {
        final Resource readOnly = new ResourceWrapper(this.context.create().resource("/Submissions/s1", Map.of()))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> ExtractionStatus.releaseClaim(readOnly));
    }

    @Test
    void refusesASubmissionItCannotWrite()
    {
        final Resource readOnly = new ResourceWrapper(this.context.create().resource("/Submissions/s1", Map.of()))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class,
            () -> ExtractionStatus.recordStatus(readOnly, ExtractionStatus.STATUS_DONE, null));
        assertThrows(PersistenceException.class, () -> ExtractionStatus.markBlocked(readOnly));
    }
}
