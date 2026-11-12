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

import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link FinishDecisionsHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FinishDecisionsHandlerTest
{
    private static final String JOB_ID = "job-1";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final FinishDecisionsHandler handler = new FinishDecisionsHandler();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, ExtractionStatus.STATUS_RUNNING);
        properties.put(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.TRUE);
        properties.put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, JOB_ID);
    }

    private void finish(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.of(target, payload, new HashMap<>()));
    }

    private ValueMap getProperties()
    {
        return this.submission.getValueMap();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("finishDecisions", this.handler.getName());
    }

    @Test
    void endsTheReadingWithTheMessageAndLetsTheClaimGo() throws Exception
    {
        finish(this.submission, Map.of(ClaimReadingHandler.JOB_KEY, JOB_ID,
            FinishDecisionsHandler.MESSAGE_KEY, "Confirm the category to continue"));

        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Confirm the category to continue", getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY));
    }

    @Test
    void endsTheReadingWithoutAMessage() throws Exception
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.MESSAGE_PROPERTY, "Old note");

        finish(this.submission, Map.of(ClaimReadingHandler.JOB_KEY, JOB_ID));

        assertEquals(ExtractionStatus.STATUS_DONE, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperties().get(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // After a Stop or a Read again, the reading is someone else's to end
    @Test
    void refusesAReadingThatIsNoLongerThisJobs()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, "job-2");

        assertThrows(InvalidStateException.class,
            () -> finish(this.submission, Map.of(ClaimReadingHandler.JOB_KEY, JOB_ID)));
        assertEquals(ExtractionStatus.STATUS_RUNNING, getProperties().get(ExtractionStatus.STATUS_PROPERTY));
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

        assertThrows(PersistenceException.class,
            () -> finish(readOnly, Map.of(ClaimReadingHandler.JOB_KEY, JOB_ID)));
    }
}
