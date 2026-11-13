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

import javax.jcr.RepositoryException;
import javax.jcr.version.VersionManager;

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
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private SubmissionTree tree;

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.submission = this.tree.createSubmission();
    }

    private void claim(final Resource target, final String jobId) throws Exception
    {
        claim(target, Map.of(ReadingEvents.JOB_KEY, jobId));
    }

    private void claim(final Resource target, final Map<String, Object> payload) throws Exception
    {
        this.handler.execute(TaskContexts.createContext(target, payload, new HashMap<>()));
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

        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // Next started a new reading after the job checked, and before its claim committed
    @Test
    void refusesAJobQueuedForAnEarlierReading()
    {
        this.submission.adaptTo(ModifiableValueMap.class).put(ExtractionStatus.READING_ID_PROPERTY, "forms");

        assertThrows(InvalidStateException.class, () -> claim(this.submission,
            Map.of(ReadingEvents.JOB_KEY, JOB_ID, ReadingEvents.READING_ID_KEY, "checks")));

        assertNull(getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    // Sling runs an unfinished job again after a restart, with the same id
    @Test
    void keepsAClaimThatIsAlreadyItsOwn() throws Exception
    {
        this.tree.startReadingAs(JOB_ID);

        claim(this.submission, JOB_ID);

        assertEquals(JOB_ID, getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesAReadingAnotherJobHas()
    {
        this.tree.startReadingAs("job-2");

        assertThrows(InvalidStateException.class, () -> claim(this.submission, JOB_ID));
        assertEquals("job-2", getProperties().get(ExtractionStatus.READING_CLAIMED_BY_PROPERTY, String.class));
    }

    @Test
    void refusesAnEventThatNamesNoJob()
    {
        assertThrows(InvalidPayloadException.class, () -> claim(this.submission, Map.of()));
    }

    @Test
    void failsWhenTheSubmissionCannotBeWritten()
    {
        final Resource readOnly = SubmissionTree.wrapReadOnly(this.submission);

        assertThrows(PersistenceException.class, () -> claim(readOnly, JOB_ID));
    }

    // A checked-in submission is read-only, with everything under it, until the repository lets it be checked out
    @Test
    void failsWhenTheSubmissionCannotBeCheckedOut() throws Exception
    {
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.doThrow(new RepositoryException("no right")).when(versions).checkout(this.submission.getPath());
        final Resource locked = SubmissionTree.wrapCheckedIn(this.submission, versions);

        assertThrows(PersistenceException.class, () -> claim(locked, JOB_ID));
    }
}
