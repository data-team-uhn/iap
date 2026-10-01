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
package io.uhndata.iap.documents.internal;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.api.ParseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaleParseJobSweeper}: which jobs are given up on, and what goes with them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class StaleParseJobSweeperTest
{
    private static final String OLD_JOB = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String YOUNG_JOB = "1f0b2ec8-3b3c-4a0b-9c07-4d4f0a2c9e11";

    private static final String TARGET = "/Submissions/ab/cd/ef/aRequest/d1/v1/file";

    private static final String STAGED = "/shared-docs/x/proposal.pdf";

    private final SlingContext context = new SlingContext();

    private final ParseOutcomeDispatcher dispatcher = new ParseOutcomeDispatcher();

    /** What the sweeper asked to be taken off the volume. */
    private final List<String> discarded = new ArrayList<>();

    /** What the handlers were told. */
    private final List<ParseOutcome> handed = new ArrayList<>();

    private StaleParseJobSweeper sweeper;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(ParseJob.JOBS_PATH);
        this.dispatcher.bindHandler(outcome -> {
            this.handed.add(outcome);
            return true;
        });
        this.sweeper = sweeperFor(this.context.resourceResolver());
        this.sweeper.activate(Map.of());
    }

    @Test
    void givesUpOnAJobTheDaemonNeverAnsweredFor()
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);

        this.sweeper.run();

        final Resource swept = this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB));
        assertNull(swept, "the record goes once a handler has taken the outcome");
        assertEquals(1, this.handed.size());
        assertEquals(OLD_JOB, this.handed.get(0).jobId());
        assertEquals(TARGET, this.handed.get(0).target());
        assertTrue(!this.handed.get(0).succeeded());
        assertTrue(this.handed.get(0).error().contains("No outcome arrived within 30 minutes"),
            this.handed.get(0).error());
    }

    // The abandoned document and anything half written beside it, such as a .tmp a killed worker never renamed
    @Test
    void takesTheStagingFolderOffTheVolumeWithIt()
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);

        this.sweeper.run();

        assertEquals(List.of(STAGED), this.discarded);
    }

    // A job that never reached the daemon has no started time, so the wait is measured from when it was queued
    @Test
    void givesUpOnAJobThatNeverLeftTheQueueEither()
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_QUEUED, null, TARGET);
        edit(job).put(ParseJob.PN_CREATED, minutesAgo(45));

        this.sweeper.run();

        assertEquals(1, this.handed.size());
    }

    @Test
    void leavesAJobThatIsStillWithinItsTime()
    {
        job(YOUNG_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(5), TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(YOUNG_JOB)));
        assertTrue(this.handed.isEmpty());
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void leavesAJobThatAlreadyEnded()
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_COMPLETED, minutesAgo(45), TARGET);
        edit(job).put(ParseJob.PN_OUTPUTS, new String[] { "/shared-docs/x/proposal.md" });

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.handed.isEmpty());
    }

    // Neither timestamp means nothing to judge the wait by, and guessing would fail a parse that may be running
    @Test
    void leavesAJobWithNoTimestampsAtAll()
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, null, TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    // A parse nobody is waiting on in the repository still has to stop saying "active" and still leaves a folder
    @Test
    void givesUpOnAPollOnlyJobWithoutHandingItToAnybody()
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), null);

        this.sweeper.run();

        final Resource swept = this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB));
        assertNotNull(swept, "a job nobody claims keeps its record for polling");
        assertEquals(ParseJob.STATUS_FAILED, swept.getValueMap().get(ParseJob.PN_STATUS, String.class));
        assertEquals(List.of(STAGED), this.discarded);
        assertTrue(this.handed.isEmpty());
    }

    // A job queued with no target is nobody's to hand over, so its record is kept for polling - and was kept for
    // good, with its staging folder, because nothing deleted it and this sweep only looked at unfinished jobs
    @Test
    void dropsTheRecordOfASettledJobNobodyCameBackFor()
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_COMPLETED, minutesAgo(90), null);
        edit(job).put(ParseJob.PN_FINISHED, minutesAgo(45));

        this.sweeper.run();

        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertEquals(List.of(STAGED), this.discarded, "and the volume goes with it");
    }

    // The same for a job whose handler refused the outcome: its record stays behind and nothing retries it
    @Test
    void dropsTheRecordOfASettledJobNoHandlerTook()
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_FAILED, minutesAgo(90), TARGET);
        edit(job).put(ParseJob.PN_FINISHED, minutesAgo(45));

        this.sweeper.run();

        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.handed.isEmpty(), "the outcome was handed over when it settled, not now");
    }

    // The record is worth keeping while somebody might still poll it
    @Test
    void keepsTheRecordOfAJobThatSettledRecently()
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_COMPLETED, minutesAgo(90), null);
        edit(job).put(ParseJob.PN_FINISHED, minutesAgo(5));

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void survivesARecordItCannotDrop() throws Exception
    {
        final Resource job = job(OLD_JOB, ParseJob.STATUS_COMPLETED, minutesAgo(90), null);
        edit(job).put(ParseJob.PN_FINISHED, minutesAgo(45));
        this.context.resourceResolver().commit();
        set(this.sweeper, "resolverFactory", factoryFor(refusingCommit(this.context.resourceResolver())));

        this.sweeper.run();

        assertEquals(List.of(STAGED), this.discarded, "the volume is cleared even so");
    }

    @Test
    void sweepsEveryStaleJobInOnePass()
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);
        job(YOUNG_JOB, ParseJob.STATUS_QUEUED, minutesAgo(90), TARGET);

        this.sweeper.run();

        assertEquals(2, this.handed.size());
    }

    @Test
    void doesNothingWhenTheJobsStorageIsNotThere() throws Exception
    {
        this.context.resourceResolver().delete(this.context.resourceResolver().getResource(ParseJob.JOBS_PATH));
        this.context.resourceResolver().commit();

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
    }

    @Test
    void survivesAStorageItCannotOpen() throws Exception
    {
        final ResourceResolverFactory refusing = Mockito.mock(ResourceResolverFactory.class);
        Mockito.when(refusing.getServiceResourceResolver(Mockito.anyMap()))
            .thenThrow(new LoginException("no such subservice"));
        set(this.sweeper, "resolverFactory", refusing);

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
    }

    @Test
    void survivesAJobNodeItCannotWrite() throws Exception
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);
        set(this.sweeper, "resolverFactory", factoryFor(unmodifiable(this.context.resourceResolver())));

        this.sweeper.run();

        assertTrue(this.handed.isEmpty());
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void survivesACommitThatIsRefused() throws Exception
    {
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);
        set(this.sweeper, "resolverFactory", factoryFor(refusingCommit(this.context.resourceResolver())));

        this.sweeper.run();

        assertTrue(this.handed.isEmpty(), "nothing is handed over that was not recorded first");
        assertTrue(this.discarded.isEmpty());
    }

    @Test
    void honoursAConfiguredMaximumAge()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "120"));
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);

        this.sweeper.run();

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(OLD_JOB)));
    }

    @Test
    void fallsBackToTheDefaultForAnUnusableMaximumAge()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "half an hour"));
        job(OLD_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(45), TARGET);

        this.sweeper.run();

        assertEquals(1, this.handed.size());
    }

    @Test
    void fallsBackToTheDefaultForAMaximumAgeOfZero()
    {
        this.sweeper.activate(Map.of(StaleParseJobSweeper.MAX_AGE_PROPERTY, "0"));
        job(YOUNG_JOB, ParseJob.STATUS_ACTIVE, minutesAgo(5), TARGET);

        this.sweeper.run();

        assertTrue(this.handed.isEmpty(), "zero would give up on everything the moment it was queued");
    }

    private Resource job(final String jobId, final String status, final Calendar started, final String target)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParseJob.PN_JOB_ID, jobId);
        properties.put(ParseJob.PN_STATUS, status);
        properties.put(ParseJob.PN_PATH, STAGED);
        if (started != null) {
            properties.put(ParseJob.PN_STARTED, started);
        }
        if (target != null) {
            properties.put(ParseJob.PN_TARGET, target);
        }
        return this.context.create().resource(ParseJob.nodePath(jobId), properties);
    }

    private static ModifiableValueMap edit(final Resource resource)
    {
        return Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class));
    }

    private static Calendar minutesAgo(final int minutes)
    {
        final Calendar when = Calendar.getInstance();
        when.add(Calendar.MINUTE, -minutes);
        return when;
    }

    /**
     * A sweeper wired to the test session, a dispatcher that records, and a parse service that records what it
     * was asked to remove rather than touching a filesystem.
     */
    private StaleParseJobSweeper sweeperFor(final ResourceResolver resolver) throws Exception
    {
        final StaleParseJobSweeper built = new StaleParseJobSweeper();
        set(built, "resolverFactory", factoryFor(resolver));
        set(built, "outcomes", this.dispatcher);
        set(built, "parseService", new RecordingParseService(this.discarded));
        return built;
    }

    private static ResourceResolverFactory factoryFor(final ResourceResolver resolver) throws LoginException
    {
        final ResourceResolverFactory factory = Mockito.mock(ResourceResolverFactory.class);
        // Never closed by the test, so the shared session survives the sweep
        Mockito.when(factory.getServiceResourceResolver(Mockito.anyMap())).thenReturn(new ResourceResolverWrapper(
            resolver)
        {
            @Override
            public void close()
            {
                // The context owns this session
            }
        });
        return factory;
    }

    /** A session whose job nodes refuse to be changed. */
    private static ResourceResolver unmodifiable(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                return wrap(super.getResource(path));
            }

            private Resource wrap(final Resource resource)
            {
                if (resource == null) {
                    return null;
                }
                final List<Resource> children = new ArrayList<>();
                resource.getChildren().forEach(child -> children.add(wrap(child)));
                final Resource spy = Mockito.spy(resource);
                Mockito.doReturn(null).when(spy).adaptTo(ModifiableValueMap.class);
                Mockito.doReturn(children).when(spy).getChildren();
                return spy;
            }

            @Override
            public void close()
            {
                // The context owns this session
            }
        };
    }

    /** A session that will not commit. */
    private static ResourceResolver refusingCommit(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public void commit() throws PersistenceException
            {
                throw new PersistenceException("the repository said no");
            }

            @Override
            public void close()
            {
                // The context owns this session
            }
        };
    }

    private static void set(final Object target, final String field, final Object value) throws Exception
    {
        final Field declared = StaleParseJobSweeper.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    /** Records what it was asked to take off the volume; a sweep must not need a real one. */
    private static final class RecordingParseService implements ParseService
    {
        private final List<String> discarded;

        RecordingParseService(final List<String> discarded)
        {
            this.discarded = discarded;
        }

        @Override
        public String stage(final String fileName, final InputStream content)
        {
            throw new UnsupportedOperationException("a sweep stages nothing");
        }

        @Override
        public String queue(final String path, final String target)
        {
            throw new UnsupportedOperationException("a sweep queues nothing");
        }

        @Override
        public boolean isStagedPath(final String path)
        {
            return path != null && !path.isBlank();
        }

        @Override
        public void discardStaging(final String stagedPath)
        {
            this.discarded.add(stagedPath);
        }
    }
}
