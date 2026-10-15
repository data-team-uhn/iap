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

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DecisionOperandResolver}: an answer counts once a person gave, confirmed or changed it, or
 * the model was sure enough of it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DecisionOperandResolverTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String VERSION_PATH = "/Schemas/proposal/v1";

    private static final String DECISION = "is_proposal/decision";

    private static final String TITLE = "common/title";

    private static final String SUBMISSION_PATH = "/Submissions/aa/bb/cc/proposal-1";

    // JCR-backed: an answer names its question, and a submission its schema version, through real references
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DecisionOperandResolver resolver = new DecisionOperandResolver();

    private Resource submission;

    private int answers;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, ClassificationRequirement.class, Question.class,
            Section.class, AnswerOption.class, Submission.class, Answer.class, Extraction.class,
            ConditionOperand.class);
        this.context.create().resource("/Schemas/proposal", TYPE, Schema.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH, TYPE, SchemaVersion.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH + "/is_proposal", Map.of(TYPE,
            ClassificationRequirement.RESOURCE_TYPE, SUPER_TYPE, "sch/Requirement", "confidenceThreshold", 0.9));
        this.context.create().resource(VERSION_PATH + "/" + DECISION,
            Map.of(TYPE, Question.RESOURCE_TYPE, "dataType", "text"));
        this.context.create().resource(VERSION_PATH + "/common",
            Map.of(TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, "sch/Requirement"));
        this.context.create().resource(VERSION_PATH + "/" + TITLE, Map.of(TYPE, Question.RESOURCE_TYPE));
        this.submission = this.context.create().resource(SUBMISSION_PATH, TYPE, Submission.RESOURCE_TYPE);
        reference(this.submission, VERSION_PATH);
    }

    private ConditionOperand operand(final String... names)
    {
        return this.context.create().resource("/Workflows/read/v1/gateway/toNext/cond:condition/operandA",
            Map.of(TYPE, ConditionOperand.RESOURCE_TYPE, "source", "decision", "value", names))
            .adaptTo(ConditionOperand.class);
    }

    private Operand resolve(final String question)
    {
        return this.resolver.resolve(operand(question), this.submission.adaptTo(Content.class));
    }

    /**
     * An answer to a question, and when a model suggested it, the extraction that did.
     *
     * @param question the question's path within the schema version
     * @param value what the answer holds, or {@code null} for none
     * @param extraction the extraction's properties, or {@code null} for an answer the submitter typed
     */
    private void answer(final String question, final String value, final Map<String, Object> extraction)
    {
        this.answers++;
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, Answer.RESOURCE_TYPE);
        if (value != null) {
            properties.put("value", new String[] { value });
        }
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/a" + this.answers, properties);
        reference(answer, VERSION_PATH + "/" + question);
        if (extraction != null) {
            final Map<String, Object> run = new HashMap<>(extraction);
            run.put(TYPE, Extraction.RESOURCE_TYPE);
            this.context.create().resource(answer.getPath() + "/run", run);
        }
    }

    private static Map<String, Object> suggested(final String value, final double confidence)
    {
        return Map.of("extractedAnswer", value, "confidence", confidence);
    }

    private void reference(final Resource from, final String toPath)
    {
        final String property = from.getPath().equals(SUBMISSION_PATH) ? "schemaVersion" : "question";
        try {
            from.adaptTo(Node.class).setProperty(property,
                this.context.resourceResolver().getResource(toPath).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void implementsTheDecisionSource()
    {
        assertEquals("decision", this.resolver.getSource());
    }

    @Test
    void countsAPickTheModelWasSureOf()
    {
        answer(DECISION, "yes", suggested("yes", 0.95));

        final Operand resolved = resolve(DECISION);

        assertEquals("yes", resolved.get(0));
        assertEquals(OperandType.TEXT, resolved.getType());
    }

    // Below the classification's own threshold, the pick waits for a person
    @Test
    void waitsOnAPickTheModelWasUnsureOf()
    {
        answer(DECISION, "yes", suggested("yes", 0.8));

        assertTrue(resolve(DECISION).isEmpty());
    }

    @Test
    void usesTheClassificationThresholdForAQuestionInASection()
    {
        this.context.create().resource(VERSION_PATH + "/is_proposal/details", TYPE, Section.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH + "/is_proposal/details/decision",
            Map.of(TYPE, Question.RESOURCE_TYPE, "dataType", "text"));
        answer("is_proposal/details/decision", "no", suggested("no", 0.8));

        assertTrue(resolve("is_proposal/details/decision").isEmpty());
    }

    @Test
    void waitsOnAPickWithNoConfidence()
    {
        answer(DECISION, "yes", Map.of("extractedAnswer", "yes"));

        assertTrue(resolve(DECISION).isEmpty());
    }

    @Test
    void countsAPickTheSubmitterConfirmed()
    {
        answer(DECISION, "yes", Map.of("extractedAnswer", "yes", "confidence", 0.4, "reviewed", true));

        assertEquals("yes", resolve(DECISION).get(0));
    }

    @Test
    void countsAPickTheSubmitterChanged()
    {
        answer(DECISION, "no", suggested("yes", 0.4));

        assertEquals("no", resolve(DECISION).get(0));
    }

    @Test
    void countsAnAnswerTheSubmitterTyped()
    {
        answer(DECISION, "yes", null);

        assertEquals("yes", resolve(DECISION).get(0));
    }

    // A reading that found nothing suggested nothing, so whatever the answer holds is the submitter's
    @Test
    void countsAnAnswerTheModelSuggestedNothingFor()
    {
        answer(DECISION, "yes", Map.of("confidence", 0.2));

        assertEquals("yes", resolve(DECISION).get(0));
    }

    @Test
    void readsAnAnswerLeftEmptyAsNoValue()
    {
        answer(DECISION, null, suggested("yes", 0.2));

        assertTrue(resolve(DECISION).isEmpty());
    }

    // A question outside a classification uses the default threshold
    @Test
    void usesTheDefaultThresholdForAnyOtherQuestion()
    {
        answer(TITLE, "A study", suggested("A study", 0.8));

        assertEquals("A study", resolve(TITLE).get(0));
    }

    @Test
    void findsNothingWhenTheQuestionIsUnanswered()
    {
        answer(TITLE, "A study", null);

        assertTrue(resolve(DECISION).isEmpty());
    }

    // A gateway is evaluated against the running instance, which sits inside the submission
    @Test
    void findsTheSubmissionFromInsideIt()
    {
        answer(DECISION, "yes", suggested("yes", 0.95));
        final Resource instance = this.context.create().resource(SUBMISSION_PATH + "/wf:instances/read",
            TYPE, "wf/WorkflowInstance");

        assertEquals("yes", this.resolver.resolve(operand(DECISION), instance.adaptTo(Content.class)).get(0));
    }

    @Test
    void resolvesNothingOutsideASubmission()
    {
        final Resource elsewhere = this.context.create().resource("/Elsewhere", TYPE, "data/Entity");

        assertTrue(this.resolver.resolve(operand(DECISION), elsewhere.adaptTo(Content.class)).isEmpty());
    }

    @Test
    void resolvesNothingForAnOperandNamingNoQuestion()
    {
        assertTrue(this.resolver.resolve(operand(), this.submission.adaptTo(Content.class)).isEmpty());
    }

    @Test
    void resolvesNothingForAnUnknownQuestion()
    {
        assertTrue(resolve("nowhere/question").isEmpty());
    }
}
