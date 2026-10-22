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
package io.uhndata.iap.submissions.internal.generation;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link SubmissionTemplateValues}: the answers reach a template nested as the schema nests its
 * questions, every question is there whether answered or not, and anything that is not a submission gets nothing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionTemplateValuesTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String VERSION_PATH = "/Schemas/study/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aStudy";

    private static final Calendar CREATED = new GregorianCalendar(2026, Calendar.OCTOBER, 21, 9, 30);

    // JCR-backed: answers and the submission point at the schema with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SubmissionTemplateValues values = new SubmissionTemplateValues();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, Section.class, Question.class, Answer.class, Submission.class);
        this.context.create().resource("/Schemas/study", Map.of(TYPE, Schema.RESOURCE_TYPE, "title", "Study"));
        this.context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0"));
        this.context.create().resource(VERSION_PATH + "/applicant", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, "sch/Requirement", "label", "Applicant"));
        question("applicant/name", false);
        question("applicant/sites", true);
        // A section, and a second "name" under it: only the tree tells the two apart
        this.context.create().resource(VERSION_PATH + "/applicant/supervisor", Map.of(
            TYPE, Section.RESOURCE_TYPE, "title", "Supervisor"));
        question("applicant/supervisor/name", false);
        // A condition holds no questions, so it is no part of what templates see
        this.context.create().resource(VERSION_PATH + "/applicant/supervisor/cond:condition", Map.of(
            "comparator", "equals"));
        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A study", "createdBy", "demo-researcher"));
        reference(SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
        // By hand, through the node, since a real repository sets it itself and the mock one does not
        try {
            Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Node.class)
                .setProperty("jcr:created", CREATED);
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void isCalledSubmission()
    {
        assertEquals("submission", this.values.getName());
    }

    @Test
    void givesTheTitleAndWhenItWasCreated()
    {
        final Map<?, ?> submission = valuesOf(SUBMISSION_PATH);

        assertEquals("A study", submission.get("title"));
        assertEquals(CREATED.getTimeInMillis(), ((Calendar) submission.get("created")).getTimeInMillis());
    }

    @Test
    void nestsTheAnswersAsTheSchemaNestsItsQuestions()
    {
        answer("1", "applicant/name", "Dr. A");
        answer("2", "applicant/sites", "Toronto", "Montreal");
        answer("3", "applicant/supervisor/name", "Dr. B");

        final Map<?, ?> applicant = (Map<?, ?>) answers().get("applicant");

        assertEquals("Dr. A", applicant.get("name"));
        assertEquals(List.of("Toronto", "Montreal"), applicant.get("sites"));
        assertEquals(Map.of("name", "Dr. B"), applicant.get("supervisor"),
            "the condition must not appear next to the question");
    }

    @Test
    void givesEveryQuestionWhetherAnsweredOrNot()
    {
        final Map<?, ?> applicant = (Map<?, ?>) answers().get("applicant");

        assertEquals("", applicant.get("name"), "an unanswered question prints nothing rather than failing");
        assertEquals(List.of(), applicant.get("sites"));
    }

    @Test
    void leavesOutAPartWithNoQuestions()
    {
        this.context.create().resource(VERSION_PATH + "/consent", Map.of(
            TYPE, "sch/DocumentRequirement", SUPER_TYPE, "sch/Requirement", "label", "Consent"));

        assertFalse(answers().containsKey("consent"));
    }

    @Test
    void hasNothingForWhatIsNotASubmission()
    {
        assertNull(this.values.getValue(Objects.requireNonNull(
            this.context.resourceResolver().getResource(VERSION_PATH))));
    }

    private Map<?, ?> answers()
    {
        return (Map<?, ?>) valuesOf(SUBMISSION_PATH).get("answers");
    }

    private Map<?, ?> valuesOf(final String path)
    {
        this.context.resourceResolver().refresh();
        return (Map<?, ?>) this.values.getValue(Objects.requireNonNull(
            this.context.resourceResolver().getResource(path)));
    }

    private void question(final String path, final boolean multiple)
    {
        this.context.create().resource(VERSION_PATH + "/" + path, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, "sch/FormItem", "text", path, "dataType", "text",
            "maxAnswers", multiple ? 0L : 1L));
    }

    private void answer(final String name, final String questionPath, final String... value)
    {
        this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", value));
        reference(SUBMISSION_PATH + "/" + name, VERSION_PATH + "/" + questionPath, "question");
    }

    private void reference(final String fromPath, final String toPath, final String property)
    {
        try {
            final Node source = Objects.requireNonNull(
                this.context.resourceResolver().getResource(fromPath)).adaptTo(Node.class);
            source.setProperty(property, Objects.requireNonNull(
                this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }
}
