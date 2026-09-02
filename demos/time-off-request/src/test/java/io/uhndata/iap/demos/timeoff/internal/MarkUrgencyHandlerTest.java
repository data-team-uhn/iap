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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.AnswerSet;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MarkUrgencyHandler}. Which requests count as urgent is {@link TimeOffUrgency}'s and tested
 * there; what this owns is the name activities point at it by, and that it judges the request the event arrived on
 * against today rather than against nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkUrgencyHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final MarkUrgencyHandler handler = new MarkUrgencyHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        taggable();
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(VERSION_PATH + "/details/startDate", Map.of(
            TYPE, Question.RESOURCE_TYPE, "text", "Which day does your time off start?", "dataType", "date"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A day off", "tags", new String[] {"submitted"}));
        this.context.create().resource(SUBMISSION_PATH + "/answers", Map.of(TYPE, AnswerSet.RESOURCE_TYPE));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(MarkUrgencyHandler.NAME, this.handler.getName());
    }

    @Test
    void flagsTheRequestTheEventArrivedOn() throws PersistenceException
    {
        // Tomorrow as the handler will read it: it judges against the day it runs, which is the only sensible
        // reading of "about to start" and the reason the date is not stored anywhere
        this.context.create().resource(SUBMISSION_PATH + "/answers/start", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "question", identifierOf(VERSION_PATH + "/details/startDate"),
            "value", new String[] {LocalDate.now().plusDays(1).toString()}));

        this.handler.execute(context(this.target));

        assertTrue(Set.of(this.context.resourceResolver().getResource(SUBMISSION_PATH)
            .getValueMap().get("tags", new String[0])).contains(TimeOffUrgency.URGENT_TAG));
    }

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

    /** The {@code Taggable} view, reading and writing the node's own {@code tags} property. */
    private void taggable()
    {
        this.context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) resource -> {
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
        });
    }

    private boolean write(final Resource resource, final String tag, final boolean placing)
        throws PersistenceException
    {
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return false;
        }
        final Set<String> tags = new LinkedHashSet<>(Set.of(properties.get("tags", new String[0])));
        final boolean changed = placing ? tags.add(tag) : tags.remove(tag);
        properties.put("tags", tags.toArray(String[]::new));
        this.context.resourceResolver().commit();
        return changed;
    }

    private WorkflowTaskContext context(final Resource resource)
    {
        final WorkflowEvent event = new WorkflowEvent("complete", Map.of());
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return resource;
            }

            @Override
            public String getActor()
            {
                return "demo-requester";
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return event;
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return MarkUrgencyHandlerTest.this.context.resourceResolver();
            }

            @Override
            public Object getVariable(final String name)
            {
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
            }
        };
    }
}
