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
package io.uhndata.iap.submissions.internal.validators;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.CompletenessFixture;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.AnswerValidator;

/**
 * Judges the smallest submission a constraint can be judged on: a schema asking one question, {@code "The
 * question"}, carrying the given constraint properties, answered with the given values. What varies between the
 * validator tests is only the constraint and the values, so that is all a test states.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ConstraintFixture
{
    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/schema/v1";

    private static final String SUBMISSION_PATH = "/Submissions/one";

    private final SlingContext context;

    private final AnswerValidator validator;

    private ConstraintFixture(final SlingContext context, final AnswerValidator validator)
    {
        this.context = context;
        this.validator = validator;
    }

    /**
     * The context a validator test needs: JCR-backed, since a submission points at its schema version, and an
     * answer at its question, with real REFERENCEs.
     *
     * @return a new context, for the test's own field
     */
    static SlingContext newContext()
    {
        return new SlingContext(ResourceResolverType.JCR_MOCK);
    }

    /**
     * A fixture under which every question is shown.
     *
     * @param context the test's own context
     * @param validator the validator under test, given its condition evaluator here
     * @return the fixture
     * @throws ReflectiveOperationException never, for the validators in this package
     */
    static ConstraintFixture showingEverything(final SlingContext context, final AnswerValidator validator)
        throws ReflectiveOperationException
    {
        return new ConstraintFixture(context, judgingUnder(validator, true));
    }

    /**
     * A fixture under which no question is shown.
     *
     * @param context the test's own context
     * @param validator the validator under test, given its condition evaluator here
     * @return the fixture
     * @throws ReflectiveOperationException never, for the validators in this package
     */
    static ConstraintFixture hidingEverything(final SlingContext context, final AnswerValidator validator)
        throws ReflectiveOperationException
    {
        return new ConstraintFixture(context, judgingUnder(validator, false));
    }

    /**
     * What the validator says about the question carrying these constraints, answered with these values.
     *
     * @param constraints the constraint properties to declare on the question, e.g. {@code maxAnswers}
     * @param values the values the answer holds; none at all leaves the question unanswered
     * @return the validator's refusal, or {@code null} when it accepts
     * @throws Exception when the fixture cannot be written
     */
    String verdictOn(final Map<String, Object> constraints, final String... values) throws Exception
    {
        return this.validator.validate(submission(constraints, values), "someone");
    }

    private Submission submission(final Map<String, Object> constraints, final String... values) throws Exception
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, SchemaVersion.class,
            FormRequirement.class, Question.class, Answer.class, Submission.class);
        this.context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0"));
        // sling:resourceSuperType is autocreated from the node type in a real repository, and by nothing in a
        // mock one, so it is set for the schema walk's type matching
        this.context.create().resource(VERSION_PATH + "/form", Map.of(TYPE, FormRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", "sch/Requirement", "label", "Details"));
        final Map<String, Object> question = new HashMap<>(constraints);
        question.put(TYPE, Question.RESOURCE_TYPE);
        question.put("sling:resourceSuperType", "sch/FormItem");
        question.putIfAbsent("text", "The question");
        this.context.create().resource(VERSION_PATH + "/form/q", question);

        final Resource submission = this.context.create().resource(SUBMISSION_PATH,
            Map.of(TYPE, Submission.RESOURCE_TYPE, "title", "One"));
        CompletenessFixture.reference(this.context, SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
        if (values.length > 0) {
            this.context.create().resource(SUBMISSION_PATH + "/a1",
                Map.of(TYPE, Answer.RESOURCE_TYPE, "value", values));
            CompletenessFixture.reference(this.context, SUBMISSION_PATH + "/a1", VERSION_PATH + "/form/q",
                "question");
        }
        return submission.adaptTo(Submission.class);
    }

    private static AnswerValidator judgingUnder(final AnswerValidator validator, final boolean shown)
        throws ReflectiveOperationException
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenReturn(shown);
        CompletenessFixture.inject(validator, "conditions", conditions);
        return validator;
    }
}
