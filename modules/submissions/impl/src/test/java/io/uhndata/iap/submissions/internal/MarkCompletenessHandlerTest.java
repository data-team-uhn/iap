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

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
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
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MarkCompletenessHandler}: that a submission is left carrying the {@code incomplete} tag
 * exactly while something it is asked for has not been answered.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class MarkCompletenessHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    /** Autocreated by a real repository from the node type, and by nothing in a mock one. */
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String FORM_ITEM = "sch/FormItem";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    /** The attachment fulfilling the schema's document requirement. */
    private static final String NOTE = "note";

    /** Where the create workflow's own event lands, which is not a submission. */
    private static final String HOMEPAGE_PATH = "/Submissions";

    /** A recorded path with nothing at it. */
    private static final String MISSING_PATH = "/Submissions/ab/cd/ef/gone";

    private static final String REQUESTER = "demo-requester";

    private static final String DETAILS = "details";

    private static final String START_DATE = "details/startDate";

    private static final String REASON = "details/why/reason";

    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    // JCR-backed: a submission points at its schema version, and an answer at its question, with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final MarkCompletenessHandler handler = new MarkCompletenessHandler();

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, DocumentRequirement.class, Section.class, Question.class,
            Answer.class, Document.class, Submission.class);
        Tagging.enable(this.context);
        // Registered as a service rather than injected into the handler: which requirements apply is the
        // submission model's question now, and the model asks for the evaluator through @OSGiService
        final ConditionEvaluator evaluator = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(evaluator.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !this.hidden.contains(((Content) call.getArgument(0)).getName()));
        this.context.registerService(ConditionEvaluator.class, evaluator);

        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS, Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        this.context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Which day?", "minAnswers", 1L));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/note", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Anything to add?"));
        // A required question one level down, so that the walk is shown to go through a section
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/why", Map.of(
            TYPE, Section.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "title", "Why"));
        this.context.create().resource(VERSION_PATH + "/" + REASON, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Why?", "minAnswers", 1L));
        // A document is something its author supplies too, so an unfulfilled one holds the tag on just as an
        // unanswered question does. Fulfilled here, so that the tests below are about the questions they name
        this.context.create().resource(VERSION_PATH + "/doctorsNote", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note"));

        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft", MarkCompletenessHandler.INCOMPLETE}));
        reference(SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
        this.context.create().resource(SUBMISSION_PATH + "/" + NOTE, Map.of(TYPE, Document.RESOURCE_TYPE));
        reference(SUBMISSION_PATH + "/" + NOTE, VERSION_PATH + "/doctorsNote", "fulfills");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(MarkCompletenessHandler.NAME, this.handler.getName());
    }

    @Test
    void leavesTheTagOnWhileSomethingRequiredIsUnanswered() throws Exception
    {
        answer(START_DATE, "2026-10-06");

        this.handler.execute(context());

        // The reason, one section down, has not been given
        assertTrue(tags().contains(MarkCompletenessHandler.INCOMPLETE));
        // And what it says about the request's lifecycle is untouched: the two are different questions
        assertTrue(tags().contains("draft"));
    }

    @Test
    void takesTheTagOffOnceEverythingRequiredIsAnswered() throws Exception
    {
        answer(START_DATE, "2026-10-06");
        answer(REASON, "A wedding");

        this.handler.execute(context());

        // The unanswered optional question and the document requirement are both beside the point
        assertFalse(tags().contains(MarkCompletenessHandler.INCOMPLETE));
        assertTrue(tags().contains("draft"));
    }

    @Test
    void doesNotAskForAQuestionThatDoesNotApply() throws Exception
    {
        // Nobody was ever asked it, so it cannot be missing — which is why this is judged against the resolved
        // form rather than the schema
        this.hidden.add("why");
        answer(START_DATE, "2026-10-06");

        this.handler.execute(context());

        assertFalse(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    @Test
    void doesNotAskForAnythingUnderARequirementThatDoesNotApply() throws Exception
    {
        this.hidden.add(DETAILS);

        this.handler.execute(context());

        assertFalse(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    @Test
    void countsABlankAnswerAsNoAnswer() throws Exception
    {
        // Clearing a field posts an empty value rather than removing the answer, so the node stays behind holding
        // nothing; treating that as answered would let a required question be satisfied by emptying it
        answer(START_DATE, "");
        answer(REASON, "   ");

        this.handler.execute(context());

        assertTrue(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    @Test
    void passesOverAnswersThatAnswerNothing() throws Exception
    {
        answer(START_DATE, "2026-10-06");
        answer(REASON, "A wedding");
        // An answer to a question the schema no longer has, and one carrying no value at all: the node type
        // permits both, and neither says anything about what is still being asked
        this.context.create().resource(SUBMISSION_PATH + "/orphan", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"stale"}));
        final Resource valueless = this.context.create().resource(SUBMISSION_PATH + "/valueless",
            Map.of(TYPE, Answer.RESOURCE_TYPE));
        reference(valueless.getPath(), VERSION_PATH + "/" + START_DATE, "question");

        this.handler.execute(context());

        assertFalse(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    /**
     * The tags the submission carries now, read back through a fresh look at the node.
     *
     * @return its tag names
     */
    @Test
    void judgesWhatTheCreateWorkflowJustMadeRatherThanItsOwnTarget() throws Exception
    {
        // The create workflow posts to the homepage, so the target here is deliberately something that is not a
        // submission at all: were the handler to read it instead of the recorded path, this would fail outright
        // rather than quietly agree
        final Resource homepage = this.context.create().resource(HOMEPAGE_PATH,
            Map.of(TYPE, "sub/SubmissionsHomepage"));
        answer(START_DATE, "2026-11-23");
        answer(REASON, "A break");

        this.handler.execute(context(homepage, SUBMISSION_PATH));

        assertFalse(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    @Test
    void refusesWhenTheRecordedPathLeadsNowhere()
    {
        final Resource homepage = this.context.create().resource(HOMEPAGE_PATH,
            Map.of(TYPE, "sub/SubmissionsHomepage"));

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context(homepage, MISSING_PATH)));

        assertTrue(refusal.getMessage().contains(MISSING_PATH));
    }

    @Test
    void holdsTheTagOnWhileADocumentTheSchemaAsksForIsMissing() throws Exception
    {
        // The other half of what an author supplies. Everything askable is answered here, so the questions cannot
        // be what is holding it — only the missing attachment can
        answer(START_DATE, "2026-11-23");
        answer(REASON, "A break");
        this.context.resourceResolver().delete(
            Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH + "/" + NOTE)));

        this.handler.execute(context());

        assertTrue(tags().contains(MarkCompletenessHandler.INCOMPLETE));
    }

    private Set<String> tags()
    {
        return Set.of(Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .getValueMap().get("tags", new String[0]));
    }

    /**
     * Records an answer to one of the schema's questions.
     *
     * @param questionPath the question's path, relative to the schema version
     * @param value what was answered
     * @throws Exception when the reference cannot be written
     */
    private void answer(final String questionPath, final String value) throws Exception
    {
        final Resource answer = this.context.create().resource(
            SUBMISSION_PATH + "/" + questionPath.replace('/', '-'),
            Map.of(TYPE, Answer.RESOURCE_TYPE, "value", new String[] {value}));
        reference(answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
    }

    private void reference(final String fromPath, final String toPath, final String property) throws Exception
    {
        final Node source = Objects.requireNonNull(
            this.context.resourceResolver().getResource(fromPath)).adaptTo(Node.class);
        final Node target = Objects.requireNonNull(
            this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class);
        Objects.requireNonNull(source).setProperty(property, Objects.requireNonNull(target));
        this.context.resourceResolver().commit();
    }

    private WorkflowTaskContext context()
    {
        return context(Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH)), null);
    }

    /**
     * A context for a task acting on what an earlier activity created rather than on its own target.
     *
     * @param target the event's target, which for the create workflow is the homepage
     * @param createdPath the path recorded as created, or {@code null} when the target is itself the subject
     * @return the context to hand the handler
     */
    private WorkflowTaskContext context(final Resource target, final String createdPath)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return target;
            }

            @Override
            public String getActor()
            {
                return REQUESTER;
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return null;
            }

            @Override
            public Activity getActivity()
            {
                return null;
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public Object getVariable(final String name)
            {
                return WorkflowResult.CREATED_PATH.equals(name) ? createdPath : null;
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                // Nothing this handler records is a variable
            }
        };
    }

}
