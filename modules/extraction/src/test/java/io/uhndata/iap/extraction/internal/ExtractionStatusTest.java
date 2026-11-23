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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractionStatus}: saving the reading's status, message and claim on a submission.
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
    void releasesTheClaim() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-1"));

        ExtractionStatus.releaseClaim(submission);

        assertNull(submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesToReleaseAClaimItCannotWrite()
    {
        final Resource readOnly =
            SubmissionTree.wrapReadOnly(this.context.create().resource("/Submissions/s1", Map.of()));

        assertThrows(PersistenceException.class, () -> ExtractionStatus.releaseClaim(readOnly));
    }

    @Test
    void refusesASubmissionItCannotWrite()
    {
        final Resource readOnly =
            SubmissionTree.wrapReadOnly(this.context.create().resource("/Submissions/s1", Map.of()));

        assertThrows(PersistenceException.class,
            () -> ExtractionStatus.recordStatus(readOnly, ExtractionStatus.STATUS_DONE, null));
    }

    @Test
    void startsAReadingNobodyHoldsYet() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED,
            ExtractionStatus.MESSAGE_PROPERTY, "The model could not be reached",
            ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-1",
            ExtractionStatus.READING_ID_PROPERTY, "r1"));

        final String readingId = ExtractionStatus.startReading(submission);

        assertTrue(ExtractionStatus.isRunning(submission));
        assertNull(submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        assertNull(submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
        assertNotEquals("r1", readingId);
        assertEquals(readingId, submission.getValueMap().get(ExtractionStatus.READING_ID_PROPERTY, String.class));
    }

    @Test
    void endsTheReadingAndLetsTheClaimGo() throws PersistenceException
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING,
            ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-1"));

        ExtractionStatus.endReading(submission, "Confirm the category to continue");

        assertFalse(ExtractionStatus.isRunning(submission));
        assertEquals(ExtractionStatus.STATUS_DONE,
            submission.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals("Confirm the category to continue",
            submission.getValueMap().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        assertNull(submission.getValueMap().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void checksOutForTheJobThatHoldsTheClaim()
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-1"));

        assertDoesNotThrow(() -> ExtractionStatus.checkOutForJob(submission, "job-1"));
    }

    @Test
    void refusesAJobWhoseClaimAnotherJobTook()
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of(
            ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-2"));

        assertThrows(InvalidStateException.class, () -> ExtractionStatus.checkOutForJob(submission, "job-1"));
    }

    // A Stop takes the claim down, so a late result must not land on the stopped reading
    @Test
    void refusesAJobWhenNobodyHoldsTheClaim()
    {
        final Resource submission = this.context.create().resource("/Submissions/s1", Map.of());

        assertThrows(InvalidStateException.class, () -> ExtractionStatus.checkOutForJob(submission, "job-1"));
    }
}
