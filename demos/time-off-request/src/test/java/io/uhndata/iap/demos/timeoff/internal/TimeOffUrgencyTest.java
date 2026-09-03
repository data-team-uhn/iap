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
package io.uhndata.iap.demos.timeoff.internal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeOffUrgency}: which requests count as about to start, and what marking one does.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TimeOffUrgencyTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    /** A Tuesday, so that "today or tomorrow" is never accidentally a weekend rule. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    // JCR-backed: an answer names its question by JCR identifier, and getNodeByIdentifier is a real lookup
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Question.class, Answer.class, Submission.class);
        taggable();
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, "sch/FormRequirement", "label", "Request details"));
        this.context.create().resource(VERSION_PATH + "/details/startDate", Map.of(
            TYPE, Question.RESOURCE_TYPE, "text", "Which day does your time off start?", "dataType", "date"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A day off", "tags", new String[] {"submitted"}));
    }

    @Test
    void flagsARequestStartingTomorrow() throws PersistenceException
    {
        answerStartDate("2026-09-16");

        TimeOffUrgency.mark(this.target, TODAY);

        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void flagsARequestStartingToday() throws PersistenceException
    {
        answerStartDate("2026-09-15");

        TimeOffUrgency.mark(this.target, TODAY);

        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void flagsARequestWhoseTimeOffHasAlreadyBegun() throws PersistenceException
    {
        // Not less pressing for having been left: dropping the flag once the date passes would hide the worst
        // case rather than the settled one
        answerStartDate("2026-09-01");

        TimeOffUrgency.mark(this.target, TODAY);

        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void leavesARequestStartingTheDayAfterTomorrow() throws PersistenceException
    {
        answerStartDate("2026-09-17");

        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void takesTheFlagBackOffWhenTheDateMovesLater() throws PersistenceException
    {
        // The half a flag that only ever went on would miss, and the reason marking is unconditional
        answerStartDate("2026-09-16");
        TimeOffUrgency.mark(this.target, TODAY);
        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));

        answerStartDate("2026-10-30");
        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void leavesARequestThatHasAlreadyBeenDecided() throws PersistenceException
    {
        // Urgency is about what still needs somebody. A decided request needs nobody, and flagging it would be
        // noise in the one list the flag exists to make readable
        answerStartDate("2026-09-16");
        modify(this.target, "tags", new String[] {"approved"});

        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void doesNotWriteToARequestThatHasAlreadyBeenDecided() throws PersistenceException
    {
        // Not merely "does not flag it" but "does not touch it": the nightly sweep sees every decided request
        // there has ever been, and re-deciding a finished one every night would be editing a closed record
        answerStartDate("2026-11-30");
        modify(this.target, "tags", new String[] {"rejected", TimeOffUrgency.URGENT_TAG});
        final Taggable taggable = Objects.requireNonNull(this.target.adaptTo(Taggable.class));

        TimeOffUrgency.mark(this.target, TODAY);

        // The flag it was carrying when it was decided is part of that record, and stays
        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));
        Mockito.verify(taggable, Mockito.never()).tag(Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(taggable, Mockito.never()).untag(Mockito.anyString(), Mockito.anyBoolean());
    }

    @Test
    void leavesARequestThatHasNotSaidWhenItStarts() throws PersistenceException
    {
        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void leavesARequestWhoseStartDateIsBlank() throws PersistenceException
    {
        // Clearing a date leaves the answer node behind holding nothing, which is not a date
        answerStartDate("   ");

        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void leavesARequestWhoseStartDateCannotBeRead() throws PersistenceException
    {
        // One unreadable answer must not stop a nightly sweep reaching the rest, so it is reported and skipped
        answerStartDate("next Tuesday");

        TimeOffUrgency.mark(this.target, TODAY);

        assertFalse(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void readsADateThatCameWithATimeOfDay() throws PersistenceException
    {
        // A `date` answer is stored as a day, but a deployment that posted a full timestamp should not be told
        // its request starts never
        answerStartDate("2026-09-16T09:30:00.000-04:00");

        TimeOffUrgency.mark(this.target, TODAY);

        assertTrue(tags().contains(TimeOffUrgency.URGENT_TAG));
    }

    @Test
    void doesNothingToSomethingThatCannotBeReadAsARequest() throws PersistenceException
    {
        // Mocked rather than a plain resource, because adapting is not a type filter: sling-mock registers the
        // models it finds on the classpath, and a resource of no particular type still adapts to Submission — it
        // simply answers nothing. The guard is for the case where adaptation genuinely fails, and it has to be
        // asked before the tags view, which throws outright where the tags service is missing.
        final Resource unreadable = Mockito.mock(Resource.class);
        Mockito.when(unreadable.adaptTo(Submission.class)).thenReturn(null);

        TimeOffUrgency.mark(unreadable, TODAY);

        Mockito.verify(unreadable, Mockito.never()).adaptTo(Taggable.class);
    }

    /** The answer to the start date question, replacing whatever was there. */
    private void answerStartDate(final String value)
    {
        final Resource existing = this.context.resourceResolver().getResource(SUBMISSION_PATH + "/start");
        if (existing != null) {
            modify(existing, "value", new String[] {value});
            return;
        }
        this.context.create().resource(SUBMISSION_PATH + "/start", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "question", identifierOf(VERSION_PATH + "/details/startDate"),
            "value", new String[] {value}));
    }

    /** The JCR identifier of the node at this path, which is how an answer names its question. */
    private String identifierOf(final String path)
    {
        try {
            final Resource resource = this.context.resourceResolver().getResource(path);
            assertNotNull(resource);
            final Node node = resource.adaptTo(Node.class);
            assertNotNull(node);
            return node.getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> tags()
    {
        return Set.of(this.context.resourceResolver().getResource(SUBMISSION_PATH)
            .getValueMap().get("tags", new String[0]));
    }

    private void modify(final Resource resource, final String property, final Object value)
    {
        try {
            final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
            assertNotNull(properties);
            properties.put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Makes the {@code Taggable} view available, reading and writing the node's own {@code tags} property — which
     * is what the tags service does for real, and which this demo has no business reimplementing.
     */
    private void taggable()
    {
        // One view per resource, kept: a fresh mock on every adaptTo would make "was this ever written to?"
        // unanswerable, since the test would be looking at a different mock from the one the subject used
        final Map<String, Taggable> views = new HashMap<>();
        this.context.registerAdapter(Resource.class, Taggable.class,
            (Function<Resource, Taggable>) resource -> views.computeIfAbsent(resource.getPath(),
                path -> this.taggableView(resource)));
    }

    /** A Taggable that reads and writes the resource's own {@code tags} property. */
    private Taggable taggableView(final Resource resource)
    {
        final Taggable taggable = Mockito.mock(Taggable.class);
        Mockito.when(taggable.hasOwnTag(Mockito.anyString())).thenAnswer(invocation ->
            Set.of(resource.getValueMap().get("tags", new String[0])).contains(invocation.getArgument(0)));
        try {
            Mockito.when(taggable.tag(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> write(resource, invocation.getArgument(0), true));
            Mockito.when(taggable.untag(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> write(resource, invocation.getArgument(0), false));
        } catch (final PersistenceException e) {
            // Declared by the methods being stubbed, thrown by neither the stubbing nor the mock
            throw new IllegalStateException(e);
        }
        return taggable;
    }

    private boolean write(final Resource resource, final String tag, final boolean placing)
        throws PersistenceException
    {
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return false;
        }
        final Set<String> tags = new java.util.LinkedHashSet<>(
            Set.of(properties.get("tags", new String[0])));
        final boolean changed = placing ? tags.add(tag) : tags.remove(tag);
        properties.put("tags", tags.toArray(String[]::new));
        this.context.resourceResolver().commit();
        return changed;
    }
}
