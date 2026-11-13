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
package io.uhndata.iap.submissions.internal.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RecordSuggestionDistancesHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RecordSuggestionDistancesHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String ANSWER = SUBMISSION_PATH + "/answer";

    private static final String EDIT_DISTANCE = "editDistance";

    private static final String REQUESTER = "demo-requester";

    private final SlingContext context = new SlingContext();

    private final RecordSuggestionDistancesHandler handler = new RecordSuggestionDistancesHandler();

    private Resource submission;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Submission.class,
            Answer.class, Extraction.class);
        this.submission = this.context.create().resource(SUBMISSION_PATH, Map.of(TYPE, Submission.RESOURCE_TYPE));
    }

    private void createAnswer(final String... values)
    {
        this.context.create().resource(ANSWER, Map.of(TYPE, Answer.RESOURCE_TYPE, "value", values));
    }

    private Resource createRun(final String name, final String... suggested)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(TYPE, Extraction.RESOURCE_TYPE));
        if (suggested.length > 0) {
            properties.put("extractedValues", suggested);
        }
        return this.context.create().resource(ANSWER + "/" + name, properties);
    }

    private void recordDistances() throws Exception
    {
        this.handler.execute(RecordingTaskContext.aimedAt(this.submission).actingFor(REQUESTER).build());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("recordSuggestionDistances", this.handler.getName());
    }

    // How far the answer sent is from the suggestion shows what the submitter did with it
    @Test
    void measuresAnAnswerAgainstItsSuggestion() throws Exception
    {
        createAnswer("2026-10-13");
        final Resource run = createRun("run", "2026-10-06");

        recordDistances();

        assertEquals(2L, run.getValueMap().get(EDIT_DISTANCE, Long.class));
        assertEquals(20.0, run.getValueMap().get("percentageDistance", Double.class));
    }

    @Test
    void measuresASuggestionTakenAsItStoodAsNoDistance() throws Exception
    {
        createAnswer("2026-10-06");
        final Resource run = createRun("run", "2026-10-06");

        recordDistances();

        assertEquals(0L, run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // Several values say the same thing in any order
    @Test
    void measuresTheSuggestedValuesTakenInAnotherOrderAsNoDistance() throws Exception
    {
        createAnswer("St Michael's", "Sunnybrook");
        final Resource run = createRun("run", "Sunnybrook", "St Michael's");

        recordDistances();

        assertEquals(0L, run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // The newest run is the suggestion the submitter last had
    @Test
    void measuresOnlyTheNewestRun() throws Exception
    {
        createAnswer("2026-10-06");
        final Resource older = createRun("older", "2026-10-01");
        final Resource newer = createRun("newer", "2026-10-06");

        recordDistances();

        assertNull(older.getValueMap().get(EDIT_DISTANCE, Long.class));
        assertEquals(0L, newer.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // The form hides a run that repeats the one before, so the run the submitter saw is the first of the two
    @Test
    void measuresTheRunTheFormShowedWhenALaterOneRepeatsIt() throws Exception
    {
        createAnswer("B");
        final Resource shown = createRun("first", "A");
        final Resource repeat = createRun("second", "A");

        recordDistances();

        assertEquals(1L, shown.getValueMap().get(EDIT_DISTANCE, Long.class));
        assertNull(repeat.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // A request sent back and sent again keeps the measure of the first time
    @Test
    void keepsTheFirstMeasure() throws Exception
    {
        createAnswer("2027-01-01");
        final Resource run = createRun("run", "2026-10-06");
        run.adaptTo(ModifiableValueMap.class).put(EDIT_DISTANCE, 2L);

        recordDistances();

        assertEquals(2L, run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    @Test
    void measuresNothingForAnAnswerNeverGiven() throws Exception
    {
        this.context.create().resource(ANSWER, Map.of(TYPE, Answer.RESOURCE_TYPE));
        final Resource run = createRun("run", "2026-10-06");

        recordDistances();

        assertNull(run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // A field the submitter clears is saved as an empty list, not removed
    @Test
    void measuresNothingForAnAnswerCleared() throws Exception
    {
        createAnswer();
        final Resource run = createRun("run", "2026-10-06");

        recordDistances();

        assertNull(run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    // A run that found nothing suggested nothing, so there is nothing to measure against
    @Test
    void measuresNothingAgainstARunThatFoundNothing() throws Exception
    {
        createAnswer("2026-10-13");
        final Resource run = createRun("run");

        recordDistances();

        assertNull(run.getValueMap().get(EDIT_DISTANCE, Long.class));
    }

    @Test
    void measuresNothingForAnAnswerNeverRead() throws Exception
    {
        createAnswer("2026-10-13");

        recordDistances();

        assertNull(this.context.resourceResolver().getResource(ANSWER).getValueMap().get(EDIT_DISTANCE));
    }

    @Test
    void refusesAStepPlacedOnSomethingElse()
    {
        final Resource homepage = this.context.create().resource("/Submissions/home",
            Map.of(TYPE, "sub/SubmissionsHomepage"));

        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(RecordingTaskContext.aimedAt(homepage).actingFor(REQUESTER).build()));
    }

    @Test
    void failsWhenTheRunCannotBeWritten()
    {
        createAnswer("2026-10-13");
        createRun("run", "2026-10-06");
        final ResourceResolver readOnly = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return path.endsWith("/run") ? null : super.getResource(path);
            }
        };
        final Resource target = new ResourceWrapper(this.submission)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return readOnly;
            }
        };

        assertThrows(PersistenceException.class,
            () -> this.handler.execute(RecordingTaskContext.aimedAt(target).actingFor(REQUESTER).build()));
    }
}
