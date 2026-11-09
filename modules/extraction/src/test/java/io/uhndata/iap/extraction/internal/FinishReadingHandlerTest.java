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
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link FinishReadingHandler}: saving on the submission that the reading is over, so the view
 * can show it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class FinishReadingHandlerTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final FinishReadingHandler handler = new FinishReadingHandler();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.submission = tree.createSubmission();
    }

    private void setStatus(final String status, final String message)
    {
        final ModifiableValueMap properties = this.submission.adaptTo(ModifiableValueMap.class);
        properties.put(ExtractionStatus.STATUS_PROPERTY, status);
        if (message != null) {
            properties.put(ExtractionStatus.MESSAGE_PROPERTY, message);
        }
    }

    private String getProperty(final String name)
    {
        return this.submission.getValueMap().get(name, String.class);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("finishReading", this.handler.getName());
    }

    // The main reason for this step: no category was picked, so no questions were asked, and nothing else would
    // move the submission off `running`
    @Test
    void endsAReadingThatAskedNothing() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, null);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("done", getProperty(ExtractionStatus.STATUS_PROPERTY));
        assertNull(getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void saysTheReadingWaitsWhenTheStepNamesAMessage() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, null);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of(),
            Map.of("message", "Confirm the classification to go on")));

        assertEquals("done", getProperty(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Confirm the classification to go on", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // An intake step that could not read every question leaves a note; ending the reading must not lose it
    @Test
    void keepsTheNoteAnEarlierStepLeft() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, "Some questions could not be read");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("done", getProperty(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("Some questions could not be read", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The step's own message is the one its author meant to show
    @Test
    void putsItsOwnMessageOverAnEarlierNote() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, "Some questions could not be read");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of(),
            Map.of("message", "Confirm the classification to go on")));

        assertEquals("Confirm the classification to go on", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    // The document is not what the request needs: the request cannot go on, and the message says why
    @Test
    void marksTheRequestBlockedWhenTheStepSaysSo() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, null);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of(),
            Map.of("message", "This document is not a research proposal", "blocks", "true")));

        assertEquals("done", getProperty(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("This document is not a research proposal", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
        assertEquals("true", getProperty(ExtractionStatus.BLOCKED_PROPERTY));
    }

    @Test
    void leavesTheRequestOpenWhenTheStepDoesNotBlock() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_RUNNING, null);

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of(),
            Map.of("message", "Confirm the classification to go on", "blocks", false)));

        assertNull(getProperty(ExtractionStatus.BLOCKED_PROPERTY));
    }

    // An earlier step already saved how the reading ended, and it knew more than this step does
    @Test
    void leavesAReadingThatAlreadyFailedAlone() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_FAILED, "The model's answer could not be read");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("failed", getProperty(ExtractionStatus.STATUS_PROPERTY));
        assertEquals("The model's answer could not be read", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void leavesAReadingThatAlreadyFinishedAlone() throws Exception
    {
        setStatus(ExtractionStatus.STATUS_DONE, "Confirm the classification to go on");

        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertEquals("Confirm the classification to go on", getProperty(ExtractionStatus.MESSAGE_PROPERTY));
    }

    @Test
    void doesNothingForASubmissionNoReadingEverStarted() throws Exception
    {
        this.handler.execute(TaskContexts.of(this.submission, Map.of(), Map.of()));

        assertNull(getProperty(ExtractionStatus.STATUS_PROPERTY));
    }
}
