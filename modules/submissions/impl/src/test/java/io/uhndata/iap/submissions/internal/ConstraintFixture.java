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
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

/**
 * Builds the smallest submission a constraint can be judged on: a schema asking one question carrying the given
 * constraint properties, answered with the given values. What varies between the validator tests is only the
 * constraint and the values, so that is all a test states.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ConstraintFixture
{
    /** What the fixture's single question asks, quoted by the refusals under test. */
    static final String QUESTION_TEXT = "The question";

    private static final String TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/schema/v1";

    private static final String SUBMISSION_PATH = "/Submissions/one";

    private ConstraintFixture()
    {
        // Utility class
    }

    /**
     * A submission whose schema asks one question with the given constraints, answered with the given values.
     *
     * @param context the test's own Sling context, which must be JCR-backed for the references to resolve
     * @param constraints the constraint properties to declare on the question, e.g. {@code maxAnswers}
     * @param values the values the answer holds; none at all leaves the question unanswered
     * @return the submission to validate
     * @throws Exception when the fixture cannot be written
     */
    static Submission submission(final SlingContext context, final Map<String, Object> constraints,
        final String... values) throws Exception
    {
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, SchemaVersion.class,
            FormRequirement.class, Question.class, Answer.class, Submission.class);
        context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0"));
        // sling:resourceSuperType is autocreated from the node type in a real repository, and by nothing in a
        // mock one, so it is set explicitly for the schema walk's type matching
        context.create().resource(VERSION_PATH + "/form", Map.of(TYPE, FormRequirement.RESOURCE_TYPE,
            "sling:resourceSuperType", "sch/Requirement", "label", "Details"));
        final Map<String, Object> question = new HashMap<>(constraints);
        question.put(TYPE, Question.RESOURCE_TYPE);
        question.put("sling:resourceSuperType", "sch/FormItem");
        question.putIfAbsent("text", QUESTION_TEXT);
        context.create().resource(VERSION_PATH + "/form/q", question);

        final Resource submission = context.create().resource(SUBMISSION_PATH,
            Map.of(TYPE, Submission.RESOURCE_TYPE, "title", "One"));
        reference(context, SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
        if (values.length > 0) {
            context.create().resource(SUBMISSION_PATH + "/a1",
                Map.of(TYPE, Answer.RESOURCE_TYPE, "value", values));
            reference(context, SUBMISSION_PATH + "/a1", VERSION_PATH + "/form/q", "question");
        }
        return submission.adaptTo(Submission.class);
    }

    private static void reference(final SlingContext context, final String fromPath, final String toPath,
        final String property) throws Exception
    {
        final Node source = Objects.requireNonNull(
            Objects.requireNonNull(context.resourceResolver().getResource(fromPath)).adaptTo(Node.class));
        final Node target = Objects.requireNonNull(
            Objects.requireNonNull(context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
        source.setProperty(property, target);
        context.resourceResolver().commit();
    }
}
