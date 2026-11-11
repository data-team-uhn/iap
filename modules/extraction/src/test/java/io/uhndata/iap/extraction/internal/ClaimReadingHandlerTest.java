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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

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
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClaimReadingHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ClaimReadingHandlerTest
{
    private static final String JOB_ID = "job-1";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final ClaimReadingHandler handler = new ClaimReadingHandler();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
    }

    private void claim(final Resource target, final String jobId) throws Exception
    {
        this.handler.execute(TaskContexts.of(target, jobId == null ? Map.of()
            : Map.of(ClaimReadingHandler.JOB_KEY, jobId), new HashMap<>()));
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

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("claimReading", this.handler.getName());
    }

    // The claim holds the job's id, so when two jobs race their writes differ and the second commit is refused
    @Test
    void claimsAReadingNobodyHas() throws Exception
    {
        claim(this.submission, JOB_ID);

        assertTrue(getProperties().get(ExtractionStatus.READING_CLAIMED_PROPERTY, Boolean.FALSE));
        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // Sling runs an unfinished job again after a restart, with the same id
    @Test
    void keepsAClaimThatIsAlreadyItsOwn() throws Exception
    {
        setClaimedBy(JOB_ID);

        claim(this.submission, JOB_ID);

        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesAReadingAnotherJobHas()
    {
        setClaimedBy("job-2");

        assertThrows(InvalidStateException.class, () -> claim(this.submission, JOB_ID));
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesAnEventThatNamesNoJob()
    {
        assertThrows(InvalidPayloadException.class, () -> claim(this.submission, null));
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

        assertThrows(PersistenceException.class, () -> claim(readOnly, JOB_ID));
    }

    // A checked-in submission is read-only, with everything under it
    @Test
    void failsWhenTheSubmissionCannotBeCheckedOut() throws Exception
    {
        final Node checkedIn = Mockito.mock(Node.class);
        Mockito.when(checkedIn.isNodeType("mix:versionable")).thenThrow(new RepositoryException("no right"));
        final Resource locked = new ResourceWrapper(this.submission)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Node.class ? type.cast(checkedIn) : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> claim(locked, JOB_ID));
    }
}
