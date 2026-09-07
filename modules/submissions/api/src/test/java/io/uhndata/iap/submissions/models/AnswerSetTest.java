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
package io.uhndata.iap.submissions.models;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Condition;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerSet}: the grouping that lets a set of questions say what answers it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerSetTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUBMISSION_PATH = "/Submissions/submission";

    private static final String SET_PATH = SUBMISSION_PATH + "/answers";

    private static final String SCHEMA_VERSION_ID = "schema-version-uuid";

    private static final String FORM_ID = "form-uuid";

    private static final String REB_ID = "reb-uuid";

    private static final String QUESTION_ID = "q1-uuid";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
        throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, Entity.class, Submission.class, Answer.class,
            AnswerSet.class, SchemaVersion.class, FormRequirement.class, ApprovalRequirement.class,
            Question.class);
        this.context.registerService(ConditionEvaluator.class, new ConditionEvaluator()
        {
            @Override
            public boolean isSatisfied(final Condition condition, final Content subject)
            {
                return condition == null;
            }

            @Override
            public boolean applies(final Conditionable conditionable, final Content subject)
            {
                return conditionable.getCondition() == null;
            }
        });

        this.context.create().resource("/Schemas/schema/1.0", TYPE, SchemaVersion.RESOURCE_TYPE);
        this.context.create().resource("/Schemas/schema/1.0/form", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "Application form"));
        this.context.create().resource("/Schemas/schema/1.0/form/q1", Map.of(
            TYPE, Question.RESOURCE_TYPE, "sling:resourceSuperType", FormItem.RESOURCE_TYPE,
            "minAnswers", 1L, "text", "Q1"));
        this.context.create().resource("/Schemas/schema/1.0/reb", Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, "sling:resourceSuperType", Requirement.RESOURCE_TYPE,
            "label", "REB approval"));

        final Session session = Mockito.mock(Session.class);
        mockNode(session, SCHEMA_VERSION_ID, "/Schemas/schema/1.0");
        mockNode(session, FORM_ID, "/Schemas/schema/1.0/form");
        mockNode(session, REB_ID, "/Schemas/schema/1.0/reb");
        mockNode(session, QUESTION_ID, "/Schemas/schema/1.0/form/q1");
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "schemaVersion", SCHEMA_VERSION_ID));
    }

    private void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    private AnswerSet set(final String fulfills)
    {
        final Resource resource = fulfills == null
            ? this.context.create().resource(SET_PATH, TYPE, AnswerSet.RESOURCE_TYPE)
            : this.context.create().resource(SET_PATH, Map.of(
                TYPE, AnswerSet.RESOURCE_TYPE, "fulfills", fulfills));
        return resource.adaptTo(AnswerSet.class);
    }

    private void answer(final String name, final String... values)
    {
        this.context.create().resource(SET_PATH + "/" + name, Map.of(
            TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_ID, "value", values));
    }

    @Test
    void adaptsResourceToModel()
    {
        assertNotNull(this.set(FORM_ID));
    }

    @Test
    void namesTheRequirementItAnswers()
    {
        final Requirement named = this.set(FORM_ID).getFulfills();

        assertNotNull(named);
        assertEquals("/Schemas/schema/1.0/form", named.getPath());
    }

    @Test
    void namesNothingWhenItSaysNothing()
    {
        assertNull(this.set(null).getFulfills());
    }

    @Test
    void listsTheAnswersItHolds()
    {
        final AnswerSet set = this.set(FORM_ID);
        this.answer("a1", "yes");
        this.answer("a2", "no");

        assertEquals(2, set.getAnswers().size());
    }

    @Test
    void holdsNoAnswersBeforeAnyAreGiven()
    {
        assertTrue(this.set(FORM_ID).getAnswers().isEmpty());
    }

    @Test
    void meetsItsRequirementOnceEveryDemandedQuestionIsAnswered()
    {
        final AnswerSet set = this.set(FORM_ID);
        this.answer("a1", "yes");

        assertTrue(set.isFulfilling());
    }

    @Test
    void doesNotMeetItWhileADemandedQuestionIsUnanswered()
    {
        assertFalse(this.set(FORM_ID).isFulfilling());
    }

    // Nothing creates one of these, but the reference is a plain REFERENCE and content can say anything. A set
    // pointed at something that is not a set of questions has no questions to have answered, so it meets nothing
    // rather than vacuously meeting everything
    @Test
    void meetsNothingWhenItNamesSomethingThatIsNotASetOfQuestions()
    {
        assertFalse(this.set(REB_ID).isFulfilling());
    }

    @Test
    void meetsNothingWhenItNamesNoRequirementAtAll()
    {
        assertFalse(this.set(null).isFulfilling());
    }

    // Whether the questions were answered is judged against the submission, so a set outside one cannot be
    // judged at all — there is no context in which to resolve which questions are being asked
    @Test
    void meetsNothingWhenItHangsOutsideASubmission()
    {
        final AnswerSet loose = this.context.create().resource("/Loose/answers", Map.of(
            TYPE, AnswerSet.RESOURCE_TYPE, "fulfills", FORM_ID)).adaptTo(AnswerSet.class);

        assertNotNull(loose);
        assertFalse(loose.isFulfilling());
    }
}
