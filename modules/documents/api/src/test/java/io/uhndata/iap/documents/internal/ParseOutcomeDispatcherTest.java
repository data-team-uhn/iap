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

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.spi.ParseOutcomeHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ParseOutcomeDispatcher}: who is told how a job ended, and when its record goes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ParseOutcomeDispatcherTest
{
    private static final String JOB_ID = "86a4c102-4b6a-4933-bc33-cc02e0e26eb7";

    private static final String TARGET = "/Submissions/ab/cd/ef/aRequest/d1/v1/file";

    private static final String MARKDOWN = "/shared-docs/x/proposal.md";

    private static final String CHUNKS = "/shared-docs/x/Chunks";

    private final SlingContext context = new SlingContext();

    private final ParseOutcomeDispatcher dispatcher = new ParseOutcomeDispatcher();

    /** Remembers what it was handed, and answers as told. */
    private static final class RecordingHandler implements ParseOutcomeHandler
    {
        private final List<ParseOutcome> seen = new ArrayList<>();

        private final boolean takes;

        RecordingHandler(final boolean takes)
        {
            this.takes = takes;
        }

        @Override
        public boolean handle(final ParseOutcome outcome)
        {
            this.seen.add(outcome);
            return this.takes;
        }
    }

    @Test
    void handsACompletedJobToTheHandlerAndDropsTheRecord()
    {
        final RecordingHandler handler = new RecordingHandler(true);
        this.dispatcher.bindHandler(handler);
        final Resource job = completed(TARGET, MARKDOWN, CHUNKS);

        this.dispatcher.settle(this.context.resourceResolver(), job);

        assertEquals(1, handler.seen.size());
        final ParseOutcome outcome = handler.seen.get(0);
        assertEquals(JOB_ID, outcome.jobId());
        assertEquals(TARGET, outcome.target());
        assertTrue(outcome.succeeded());
        assertNull(outcome.error());
        assertEquals(MARKDOWN, outcome.markdownPath());
        assertEquals(CHUNKS, outcome.chunksPath());
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)),
            "what the record held now lives where it belongs");
    }

    @Test
    void describesAnUnchunkedParseWithNoChunkTree()
    {
        final RecordingHandler handler = new RecordingHandler(false);
        this.dispatcher.bindHandler(handler);

        this.dispatcher.settle(this.context.resourceResolver(), completed(TARGET, MARKDOWN));

        assertNull(handler.seen.get(0).chunksPath());
    }

    @Test
    void describesAFailedJobByItsError()
    {
        final RecordingHandler handler = new RecordingHandler(true);
        this.dispatcher.bindHandler(handler);
        final Resource job = this.context.create().resource(ParseJob.nodePath(JOB_ID),
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_FAILED,
            ParseJob.PN_TARGET, TARGET,
            ParseJob.PN_ERROR, "The daemon answered HTTP 500");

        this.dispatcher.settle(this.context.resourceResolver(), job);

        final ParseOutcome outcome = handler.seen.get(0);
        assertFalse(outcome.succeeded());
        assertEquals("The daemon answered HTTP 500", outcome.error());
        assertNull(outcome.markdownPath());
    }

    @Test
    void keepsTheRecordWhenNobodyTakesTheOutcome()
    {
        final RecordingHandler handler = new RecordingHandler(false);
        this.dispatcher.bindHandler(handler);

        this.dispatcher.settle(this.context.resourceResolver(), completed(TARGET, MARKDOWN, CHUNKS));

        assertEquals(1, handler.seen.size());
        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)),
            "left for somebody to look at");
    }

    @Test
    void tellsNobodyAboutAJobQueuedWithoutATarget()
    {
        final RecordingHandler handler = new RecordingHandler(true);
        this.dispatcher.bindHandler(handler);

        this.dispatcher.settle(this.context.resourceResolver(), completed(null, MARKDOWN, CHUNKS));

        assertTrue(handler.seen.isEmpty());
        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)),
            "still there to be polled");
    }

    @Test
    void aFailingHandlerDoesNotStopTheOthers()
    {
        final RecordingHandler taker = new RecordingHandler(true);
        this.dispatcher.bindHandler(outcome -> {
            throw new IllegalStateException("boom");
        });
        this.dispatcher.bindHandler(taker);

        this.dispatcher.settle(this.context.resourceResolver(), completed(TARGET, MARKDOWN, CHUNKS));

        assertEquals(1, taker.seen.size());
        assertNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)));
    }

    @Test
    void anUnboundHandlerIsNoLongerTold()
    {
        final RecordingHandler handler = new RecordingHandler(true);
        this.dispatcher.bindHandler(handler);
        this.dispatcher.unbindHandler(handler);

        this.dispatcher.settle(this.context.resourceResolver(), completed(TARGET, MARKDOWN, CHUNKS));

        assertTrue(handler.seen.isEmpty());
    }

    @Test
    void keepsARecordItCannotDelete()
    {
        this.dispatcher.bindHandler(new RecordingHandler(true));
        final ResourceResolver undeletable = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public void delete(final Resource resource) throws PersistenceException
            {
                throw new PersistenceException("Not allowed to delete " + resource.getPath());
            }
        };
        final Resource job = completed(TARGET, MARKDOWN, CHUNKS);

        this.dispatcher.settle(undeletable, job);

        assertNotNull(this.context.resourceResolver().getResource(ParseJob.nodePath(JOB_ID)),
            "a stale record is better than handing the outcome over twice");
    }

    private Resource completed(final String target, final String... outputs)
    {
        final List<Object> properties = new ArrayList<>(List.of(
            ParseJob.PN_JOB_ID, JOB_ID,
            ParseJob.PN_STATUS, ParseJob.STATUS_COMPLETED,
            ParseJob.PN_OUTPUTS, outputs));
        if (target != null) {
            properties.add(ParseJob.PN_TARGET);
            properties.add(target);
        }
        return this.context.create().resource(ParseJob.nodePath(JOB_ID), properties.toArray());
    }
}
