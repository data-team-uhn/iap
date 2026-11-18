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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

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

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.Tagging;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String REASON = "details/reason";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String VALUE = "value";

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE through the JCR API
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SaveAnswersHandler handler;

    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    /** A part shown only once some answer in the request holds "yes", if a test sets one. */
    private String revealed;

    private Resource target;

    // What the request reads as when adapted to a node: jcr-mock answers nothing about versioning, so each test says
    // whether the request is checked in
    private Node node;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.node = Mockito.mock(Node.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(true);
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, Question.class, Answer.class, Submission.class,
            Activity.class);
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenAnswer(call -> {
            final String name = ((Content) call.getArgument(0)).getName();
            return name.equals(this.revealed) ? this.saysYes() : !this.hidden.contains(name);
        });
        // The submission model asks for the evaluator through @OSGiService
        this.context.registerService(ConditionEvaluator.class, conditions);
        this.handler = this.context.registerInjectActivateService(new SaveAnswersHandler());
        // Whether a request may still be answered is read from its lifecycle tag, which needs the view the
        // tags bundle provides
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, "sch/FormRequirement", SUPER_TYPE, "sch/Requirement", "label", "Request details"));
        this.context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, "sch/FormItem", "text", "Which day does your time off start?",
            "dataType", "date"));
        this.context.create().resource(VERSION_PATH + "/" + REASON, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, "sch/FormItem", "text", "Is it for a wedding?"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SaveAnswersHandler.HANDLER_NAME, this.handler.getName());
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
    void namesTheSubmissionAnsweredForTheHistory() throws Exception
    {
        final TaskContext answering = context(Map.of(START_DATE, "2026-10-06"));

        this.handler.execute(answering);

        assertEquals(Map.of(SUBMISSION_PATH, "answered"), answering.getRoles());
    }

    @Test
    void refusesAValueForAQuestionThatDoesNotApply()
    {
        this.hidden.add("startDate");

        final InvalidStateException refusal = assertThrows(InvalidStateException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"))));

        assertEquals("These questions do not apply to this request now: " + START_DATE, refusal.getMessage());
    }

    @Test
    void acceptsClearingAQuestionThatDoesNotApply()
    {
        this.hidden.add("startDate");

        assertDoesNotThrow(() -> this.handler.execute(context(Map.of(START_DATE, ""))));
    }

    @Test
    void acceptsAQuestionTheSameSaveReveals()
    {
        this.revealed = "startDate";

        assertDoesNotThrow(() -> this.handler.execute(context(Map.of(REASON, "yes", START_DATE, "2026-10-06"))));
    }

    @Test
    void refusesAQuestionTheSameSaveKeepsHidden()
    {
        this.revealed = "startDate";

        assertThrows(InvalidStateException.class,
            () -> this.handler.execute(context(Map.of(REASON, "no", START_DATE, "2026-10-07"))));
    }

    @Test
    void checksOutTheRequestBeforeAnsweringIt() throws Exception
    {
        // A checked-in request takes no answers
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(false);
        Mockito.when(this.node.isNodeType("mix:versionable")).thenReturn(true);
        Mockito.when(this.node.getPath()).thenReturn(SUBMISSION_PATH);
        Mockito.when(this.node.getSession()).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        Mockito.doAnswer(invocation -> {
            assertFalse(this.target.hasChildren(), "An answer was written before the request was checked out");
            return null;
        }).when(versions).checkout(SUBMISSION_PATH);

        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));

        Mockito.verify(versions).checkout(SUBMISSION_PATH);
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
        // A form saved twice leaves one answer, with the later value
        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));
        stampAnswers();
        this.handler.execute(context(Map.of(START_DATE, "2026-10-13")));

        assertEquals(List.of("2026-10-13"), List.of(onlyAnswer().getValueMap().get(VALUE, new String[0])));
    }

    @Test
    void clearsAnAnswerAlreadyGiven() throws Exception
    {
        // Emptying a field clears its answer, so the question stops counting as answered
        this.handler.execute(context(Map.of(START_DATE, "2026-10-06")));
        stampAnswers();

        this.handler.execute(context(Map.of(START_DATE, "")));

        // Stored as holding no value, not as holding the empty string
        assertEquals(List.of(), List.of(onlyAnswer().getValueMap().get(VALUE, new String[0])));
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
        // The engine runs the handler privileged, so the handler has to refuse it
        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"), "somebody-else")));

        assertTrue(refusal.getMessage().contains("Only the person who raised"));
        assertTrue(submission().getAnswers().isEmpty());
    }

    @Test
    void refusesARequestThatIsNoLongerADraft()
    {
        modify(this.target, "tags", new String[] {"submitted"});

        final InvalidStateException refusal = assertThrows(InvalidStateException.class,
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
        // The path resolves, to a section
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("details", "whatever"))));
    }

    @Test
    void refusesWhenTheSchemaVersionCannotBeRead()
    {
        // The reference is set, and the resolver the save writes with cannot read its target
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
        // The answer is created, but reads as a node that fails on any use, so its reference to its question cannot
        // be written. That has to reach the engine as a persistence problem it can translate, and not as a raw
        // repository error.
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
        assertTrue(failure.getMessage().contains("Cannot point"));
    }

    /**
     * Stamps the {@code sling:resourceType} that Oak autocreates from the node type and a mock repository does not,
     * which is what makes an answer read as one. The handler cannot set it, since the property is protected.
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

    // A model registered for one type adapts a resource of another, so the wrong target is refused by its resource
    // type
    @Test
    void refusesATargetThatIsNotASubmission() throws Exception
    {
        // The fixture's target is a submission, so point this one somewhere else of a different type
        this.target = this.context.create().resource("/Submissions/notOne", TYPE, "sub/Something");

        assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"))));
    }

    // An absolute key ignores the schema version it is meant to be relative to
    @Test
    void refusesAQuestionOutsideThisSchemaVersion()
    {
        this.context.create().resource("/Schemas/other/v1", Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource("/Schemas/other/v1/secret", Map.of(
            TYPE, Question.RESOURCE_TYPE, "text", "Not yours", "dataType", "text"));

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of("/Schemas/other/v1/secret", "peek"))));
    }

    @Test
    void refusesAPayloadValueThatIsNotText()
    {
        // What arrives is checked, not coerced. String.valueOf would store a list as the single answer "[a, b]".
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, List.of("a", "b")))));
    }

    @Test
    void writesNothingWhenClearingAQuestionNobodyAnswered()
    {
        assertDoesNotThrow(() -> this.handler.execute(context(Map.of(START_DATE, ""))));

        // The createParts task creates the empty answer, not this one
        assertTrue(submission().getAnswers().isEmpty());
    }

    @Test
    void dropsBlankValues() throws Exception
    {
        this.handler.execute(context(Map.of(START_DATE, new String[] {"a", "", " ", "c"})));

        stampAnswers();
        assertEquals(List.of("a", "c"), List.of(onlyAnswer().getValueMap().get(VALUE, new String[0])));
    }

    // A schema version reference that resolves to nothing is refused, and does not fail the handler
    @Test
    void refusesWhenTheSchemaVersionReferenceDangles() throws Exception
    {
        Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .adaptTo(ModifiableValueMap.class)
            .put("schemaVersion", "00000000-dead-0000-0000-000000000000");
        this.context.resourceResolver().commit();

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(START_DATE, "2026-10-06"))));
    }

    private TaskContext context(final Map<String, Object> payload)
    {
        return context(payload, REQUESTER);
    }

    private TaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private TaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final Node asNode = this.node;
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(asNode) : super.adaptTo(type);
            }
        };
        return new TaskContext(submission, actor, new WorkflowEvent("save", payload))
            .performing(Mockito.mock(Activity.class));
    }

    /**
     * Whether some answer in the request holds "yes", read off the nodes, since an answer the handler just created
     * carries no resource type in the mock repository.
     *
     * @return {@code true} if one does
     */
    private boolean saysYes()
    {
        return StreamSupport.stream(this.target.getChildren().spliterator(), false)
            .anyMatch(child -> List.of(child.getValueMap().get(VALUE, new String[0])).contains("yes"));
    }
}
