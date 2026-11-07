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

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DecisionOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DecisionOperandResolverTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String QUESTION_ID = "11111111-2222-3333-4444-555555555555";

    private static final String ANSWER_PATH = "/Submissions/sub/a1";

    private final SlingContext context = new SlingContext();

    private final DecisionOperandResolver resolver = new DecisionOperandResolver();

    private ConditionOperand operand;

    private Content submission;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class, Answer.class,
            Extraction.class);
        this.context.create().resource("/Workflows/reading/v1", SLING_RESOURCE_TYPE, "data/Entity");
        this.operand = this.context.create().resource("/Workflows/reading/v1/gateway/cond:condition/operandA",
            Map.of(SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "source", "decision",
                "value", new String[] { QUESTION_ID }))
            .adaptTo(ConditionOperand.class);
        this.submission = this.context.create().resource("/Submissions/sub", SLING_RESOURCE_TYPE, "sub/Submission")
            .adaptTo(Content.class);
    }

    private void createSuggestion(final String suggested)
    {
        this.context.create().resource(ANSWER_PATH + "/run", Map.of(
            SLING_RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", suggested, "confidence", 0.9));
    }

    @Test
    void implementsTheDecisionSource()
    {
        assertEquals("decision", this.resolver.getSource());
    }

    // A reading never writes the value, so a step that routes on its pick reads the suggestion
    @Test
    void readsTheSuggestionToAQuestionNobodyHasAnswered()
    {
        this.context.create().resource(ANSWER_PATH, Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_ID));
        createSuggestion("no");

        final Operand resolved = this.resolver.resolve(this.operand, this.submission);

        assertEquals(1, resolved.size());
        assertEquals("no", resolved.get(0));
    }

    @Test
    void readsTheSubmittersValueOverTheSuggestion()
    {
        this.context.create().resource(ANSWER_PATH, Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_ID, "value", new String[] { "yes" }));
        createSuggestion("no");

        final Operand resolved = this.resolver.resolve(this.operand, this.submission);

        assertEquals(1, resolved.size());
        assertEquals("yes", resolved.get(0));
    }

    @Test
    void readsNothingWhenThereIsNeitherValueNorSuggestion()
    {
        this.context.create().resource(ANSWER_PATH, Map.of(
            SLING_RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", QUESTION_ID));

        assertEquals(0, this.resolver.resolve(this.operand, this.submission).size());
    }
}
