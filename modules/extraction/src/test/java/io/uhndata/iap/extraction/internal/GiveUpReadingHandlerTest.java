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

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link GiveUpReadingHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GiveUpReadingHandlerTest
{
    private static final String JOB_ID = "job-1";

    private static final String REASON = "The document could not be read";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final GiveUpReadingHandler handler = new GiveUpReadingHandler();

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
    }

    private void giveUpOwnReading(final Resource target) throws Exception
    {
        giveUp(target, Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.MESSAGE_KEY, REASON,
            GiveUpReadingHandler.CLAIMED_KEY, true));
    }

    private void giveUpBeforeClaiming(final Resource target) throws Exception
    {
        giveUp(target, Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.MESSAGE_KEY, REASON,
            GiveUpReadingHandler.CLAIMED_KEY, false));
    }

    private void giveUp(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.createContext(target, payload, new HashMap<>()));
    }

    private void setClaimedBy(final String jobId)
    {
        this.tree.startReadingAs(jobId);
    }

    private ValueMap getProperties()
    {
        return this.submission.getValueMap();
    }

    private void assertGaveUp()
    {
        assertEquals(ExtractionStatus.STATUS_FAILED,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertEquals(REASON, getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class),
            "so a later parse, or somebody asking again, can take the reading");
    }

    private void assertLeftAlone()
    {
        assertEquals(ExtractionStatus.STATUS_RUNNING,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("giveUpReading", this.handler.getName());
    }

    // The job's reason was about the reading it was queued for, not the one Next started since
    @Test
    void leavesALaterReadingAloneWhenGivingUpBeforeTheClaim() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_ID_PROPERTY, "forms");

        giveUp(this.submission, Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.MESSAGE_KEY, REASON,
            GiveUpReadingHandler.CLAIMED_KEY, false, ReadingEvents.READING_ID_KEY, "checks"));

        assertLeftAlone();
    }

    // Every queued job names its reading, and the step finds it still the current one
    @Test
    void givesUpTheReadingItWasQueuedForBeforeTheClaim() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_ID_PROPERTY, "forms");

        giveUp(this.submission, Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.MESSAGE_KEY, REASON,
            GiveUpReadingHandler.CLAIMED_KEY, false, ReadingEvents.READING_ID_KEY, "forms"));

        assertGaveUp();
    }

    @Test
    void givesUpItsOwnReading() throws Exception
    {
        setClaimedBy(JOB_ID);

        giveUpOwnReading(this.submission);

        assertGaveUp();
    }

    // `running` was committed before the job, so a job that could not even claim the reading still stops the spinner
    @Test
    void givesUpAReadingNobodyClaimed() throws Exception
    {
        giveUpBeforeClaiming(this.submission);

        assertGaveUp();
    }

    // After a stop, Read again may already have queued a new reading; a late give-up must not touch it
    @Test
    void leavesAReadingAnotherJobHasAlone() throws Exception
    {
        setClaimedBy("job-2");

        giveUpOwnReading(this.submission);

        assertLeftAlone();
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void leavesAReadingAnotherJobClaimedFirstAlone() throws Exception
    {
        setClaimedBy("job-2");

        giveUpBeforeClaiming(this.submission);

        assertLeftAlone();
    }

    // A stop takes the claim down; the reading that follows is not this job's to give up
    @Test
    void leavesAReadingWhoseClaimWasTakenDownAlone() throws Exception
    {
        giveUpOwnReading(this.submission);

        assertLeftAlone();
    }

    // The submitter stopped the reading while the job was finding its reason to give up
    @Test
    void leavesAStoppedReadingAloneWhenGivingUpBeforeTheClaim() throws Exception
    {
        final ModifiableValueMap stopped = this.submission.adaptTo(ModifiableValueMap.class);
        stopped.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_FAILED);
        stopped.put(ExtractionStatus.MESSAGE_PROPERTY, ExtractionStatus.STOPPED_MESSAGE);

        giveUpBeforeClaiming(this.submission);

        assertEquals(ExtractionStatus.STOPPED_MESSAGE,
            getProperties().get(ExtractionStatus.MESSAGE_PROPERTY, String.class));
    }

    @Test
    void leavesAFinishedReadingAloneWhenGivingUpBeforeTheClaim() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_DONE);

        giveUpBeforeClaiming(this.submission);

        assertEquals(ExtractionStatus.STATUS_DONE,
            getProperties().get(ExtractionStatus.STATUS_PROPERTY, String.class));
    }

    @Test
    void refusesAnEventThatNamesNoJob()
    {
        assertThrows(InvalidPayloadException.class,
            () -> giveUp(this.submission, Map.of(ReadingEvents.MESSAGE_KEY, REASON)));
    }

    @Test
    void refusesAnEventThatSaysNotWhy()
    {
        assertThrows(InvalidPayloadException.class,
            () -> giveUp(this.submission, Map.of(ReadingEvents.JOB_KEY, JOB_ID)));
    }

    @Test
    void failsWhenTheSubmissionCannotBeWritten()
    {
        setClaimedBy(JOB_ID);
        final Resource readOnly = SubmissionTree.wrapReadOnly(this.submission);

        assertThrows(PersistenceException.class, () -> giveUpOwnReading(readOnly));
    }
}
