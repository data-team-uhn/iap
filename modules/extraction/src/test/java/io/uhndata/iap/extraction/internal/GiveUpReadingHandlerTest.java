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
import org.apache.sling.api.resource.ResourceWrapper;
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

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.STATUS_PROPERTY,
            ExtractionStatus.STATUS_RUNNING);
    }

    private void giveUp(final Resource target, final boolean claimed) throws Exception
    {
        giveUp(target, Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.MESSAGE_KEY, REASON,
            GiveUpReadingHandler.CLAIMED_KEY, claimed));
    }

    private void giveUp(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.of(target, payload, new HashMap<>()));
    }

    private void setClaimedBy(final String jobId)
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, jobId);
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
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.class),
            "so a later parse, or somebody asking again, can take the reading");
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
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

    @Test
    void givesUpItsOwnReading() throws Exception
    {
        setClaimedBy(JOB_ID);

        giveUp(this.submission, true);

        assertGaveUp();
    }

    // `running` was committed before the job, so a job that could not even claim the reading still stops the spinner
    @Test
    void givesUpAReadingNobodyClaimed() throws Exception
    {
        giveUp(this.submission, false);

        assertGaveUp();
    }

    // After a stop, Read again may already have queued a new reading; a late give-up must not touch it
    @Test
    void leavesAReadingAnotherJobHasAlone() throws Exception
    {
        setClaimedBy("job-2");

        giveUp(this.submission, true);

        assertLeftAlone();
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void leavesAReadingAnotherJobClaimedFirstAlone() throws Exception
    {
        setClaimedBy("job-2");

        giveUp(this.submission, false);

        assertLeftAlone();
    }

    // A stop takes the claim down; the reading that follows is not this job's to give up
    @Test
    void leavesAReadingWhoseClaimWasTakenDownAlone() throws Exception
    {
        giveUp(this.submission, true);

        assertLeftAlone();
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
        final Resource readOnly = new ResourceWrapper(this.submission)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> giveUp(readOnly, true));
    }
}
