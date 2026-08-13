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
package io.uhndata.iap.submissions.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
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
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SaveAnswersHandler}: who may record answers, which questions may be answered, and what a
 * second save does to the first one's answers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SaveAnswersHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String START_DATE = "details/startDate";

    private static final String VALUE = "value";

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE through the JCR API
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SaveAnswersHandler handler = new SaveAnswersHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Question.class, Answer.class, Submission.class, Activity.class);
        // Whether a request may still be answered is read from its lifecycle tag, which needs the view the
        // tags bundle provides
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, "sch/FormRequirement", "label", "Request details"));
        this.context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, "text", "Which day does your time off start?", "dataType", "date"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SaveAnswersHandler.NAME, this.handler.getName());
    }

    @Test
    void recordsAnAnswerAgainstItsQuestion() throws Exception
    {
        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));

        stampAnswers();
        final Resource answer = onlyAnswer();
        assertEquals(List.of("2026-10-06"), List.of(answer.getValueMap().get(VALUE, new String[0])));
        // A real REFERENCE, holding the question node's own identifier
        assertEquals(identifierOf(VERSION_PATH + "/" + START_DATE),
            answer.getValueMap().get("question", String.class));
    }

    @Test
    void recordsEveryValueOfAQuestionAnsweredMoreThanOnce() throws Exception
    {
        this.handler.execute(context(Map.of(START_DATE, new String[] {"2026-10-06", "2026-10-07"})));

        stampAnswers();
        assertEquals(List.of("2026-10-06", "2026-10-07"),
            List.of(onlyAnswer().getValueMap().get(VALUE, new String[0])));
    }

    @Test
    void updatesTheAnswerAlreadyThereRatherThanAddingAnother() throws Exception
    {
        // The whole point of finding the existing answer by its reference: a form saved twice is one answer with
        // the later value, not two answers disagreeing
        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));
        stampAnswers();
        this.handler.execute(context(Map.of(START_DATE, "2026-10-13")));

        assertEquals(List.of("2026-10-13"), List.of(onlyAnswer().getValueMap().get(VALUE, new String[0])));
    }

    @Test
    void passesOverAnAnswerWhoseQuestionIsGone() throws Exception
    {
        // A question removed from the schema leaves its answer behind, and that answer is not the one being saved
        this.context.create().resource(SUBMISSION_PATH + "/orphan", Map.of(
            TYPE, Answer.RESOURCE_TYPE, VALUE, new String[] {"stale"}));

        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));

        stampAnswers();
        assertEquals(2, submission().getAnswers().size());
    }

    @Test
    void refusesSomebodyElsesRequest()
    {
        // Has to be checked here: the engine executes privileged, so whatever a handler does not refuse is allowed
        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"), "somebody-else")));

        assertTrue(refusal.getMessage().contains("Only the person who raised"));
        assertTrue(submission().getAnswers().isEmpty());
    }

    @Test
    void refusesARequestThatIsNoLongerADraft()
    {
        modify(this.target, "tags", new String[] {"submitted"});

        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"))));

        assertTrue(refusal.getMessage().contains("can no longer be changed"));
    }

    @Test
    void refusesAQuestionThisSchemaDoesNotAsk()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("details/invented", "whatever"))));

        assertTrue(refusal.getMessage().contains("no question details/invented"));
    }

    @Test
    void refusesSomethingThatIsNotAQuestion()
    {
        // The path resolves, which is the interesting case: a section is not something one answers
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("details", "whatever"))));
    }

    @Test
    void refusesWhenTheSchemaVersionCannotBeRead()
    {
        // Its reference is mandatory, so what can go wrong is not that it is missing but that this session cannot
        // see what it points at
        final ResourceResolver blind = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return VERSION_PATH.equals(path) ? null : super.getResource(path);
            }
        };

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"), REQUESTER, blind)));
    }

    @Test
    void translatesAFailedReferenceIntoAPersistenceFailure()
    {
        // The answer is created but cannot be adapted to a node, so the reference to its question cannot be
        // written. That has to reach the engine as a persistence problem it knows how to translate rather than as
        // a raw repository error escaping a handler. Sabotaged at creation because that is the one resource the
        // handler obtains through the resolver it was handed; everything else it reads comes from a resource's own.
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                return new ResourceWrapper(super.create(parent, name, properties))
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"), REQUESTER, sabotaged)));
        assertTrue(failure.getMessage().contains("Could not reference"));
    }

    /**
     * Stamps the {@code sling:resourceType} that a real repository autocreates from the node type and a mock
     * repository does not, which is what makes an answer recognisable as one. The runtime cannot do this itself —
     * the property is protected — so a test that wants to see what it wrote has to stand in for the node type.
     */
    private void stampAnswers()
    {
        this.context.resourceResolver().refresh();
        final Resource submission = present(this.context.resourceResolver().getResource(SUBMISSION_PATH));
        submission.getChildren().forEach(child -> {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))
                && child.getValueMap().get(TYPE) == null) {
                modify(child, TYPE, Answer.RESOURCE_TYPE);
            }
        });
    }

    private Submission submission()
    {
        this.context.resourceResolver().refresh();
        return present(this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Submission.class);
    }

    private Resource onlyAnswer()
    {
        final List<Answer> answers = submission().getAnswers();
        assertEquals(1, answers.size());
        return present(this.context.resourceResolver().getResource(answers.get(0).getPath()));
    }

    private Resource present(final Resource resource)
    {
        assertNotNull(resource);
        return resource;
    }

    private String identifierOf(final String path)
    {
        try {
            return present(this.context.resourceResolver().getResource(path)).adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            final Node source = from.adaptTo(Node.class);
            source.setProperty(property, present(this.context.resourceResolver().getResource(toPath))
                .adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modify(final Resource resource, final String property, final Object value)
    {
        try {
            resource.adaptTo(ModifiableValueMap.class).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        return context(payload, REQUESTER);
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent("save", payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return submission;
            }

            @Override
            public String getActor()
            {
                return actor;
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
                return resolver;
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
