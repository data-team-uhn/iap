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
package io.uhndata.iap.submissions.internal.evaluators;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.internal.CompletenessFixture;
import io.uhndata.iap.submissions.models.Answer;

import static io.uhndata.iap.submissions.internal.CompletenessFixture.NOTE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.REASON;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.START_DATE;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.SUBMISSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.VERSION_PATH;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.answer;
import static io.uhndata.iap.submissions.internal.CompletenessFixture.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link QuestionCompletenessEvaluator}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class QuestionCompletenessEvaluatorTest
{
    /** The schema parts these tests hide; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final QuestionCompletenessEvaluator evaluator = new QuestionCompletenessEvaluator();

    @BeforeEach
    void setUp() throws Exception
    {
        CompletenessFixture.inject(this.evaluator, "conditions", CompletenessFixture.setUp(this.context, this.hidden));
    }

    @Test
    void reportsTheEmptyAnswersOfRequiredQuestions() throws Exception
    {
        answer(this.context, START_DATE);
        answer(this.context, NOTE);
        answer(this.context, REASON);

        // The note is optional, so its empty answer is complete
        assertEquals(List.of(path(START_DATE), path(REASON)),
            questionsOf(this.evaluator.evaluate(submission(this.context))));
    }

    @Test
    void reportsTheSubmissionWhenARequiredQuestionHasNoAnswer() throws Exception
    {
        answer(this.context, START_DATE, "2026-10-06");

        assertEquals(List.of(SUBMISSION_PATH),
            this.evaluator.evaluate(submission(this.context)).stream().map(Resource::getPath).toList());
        // Judging creates nothing
        assertEquals(List.of(path(START_DATE)), answered());
    }

    @Test
    void ignoresAMissingAnswerToAnOptionalQuestion() throws Exception
    {
        answer(this.context, START_DATE, "2026-10-06");
        answer(this.context, REASON, "A wedding");

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void reportsNothingOnceEveryRequiredQuestionIsAnswered() throws Exception
    {
        answer(this.context, START_DATE, "2026-10-06");
        answer(this.context, REASON, "A wedding");
        answer(this.context, NOTE);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void countsABlankValueAsNoValue() throws Exception
    {
        answer(this.context, START_DATE, "   ");
        answer(this.context, REASON, "A wedding");

        assertEquals(List.of(path(START_DATE)), questionsOf(this.evaluator.evaluate(submission(this.context))));
    }

    @Test
    void reportsAQuestionGivenFewerValuesThanItAsksFor() throws Exception
    {
        this.context.resourceResolver().getResource(VERSION_PATH + "/" + START_DATE)
            .adaptTo(ModifiableValueMap.class).put("minAnswers", 2L);
        answer(this.context, START_DATE, "2026-10-06");
        answer(this.context, REASON, "A wedding");

        assertEquals(List.of(path(START_DATE)), questionsOf(this.evaluator.evaluate(submission(this.context))));
    }

    @Test
    void asksNothingUnderASectionThatDoesNotApply() throws Exception
    {
        this.hidden.add("why");
        answer(this.context, START_DATE, "2026-10-06");
        answer(this.context, REASON);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void asksNothingOfARequirementThatDoesNotApply() throws Exception
    {
        this.hidden.add(CompletenessFixture.DETAILS);

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    @Test
    void ignoresAnAnswerToNoQuestion() throws Exception
    {
        answer(this.context, START_DATE, "2026-10-06");
        answer(this.context, REASON, "A wedding");
        this.context.create().resource(SUBMISSION_PATH + "/orphan", Map.of(
            CompletenessFixture.TYPE, Answer.RESOURCE_TYPE));

        assertEquals(List.of(), this.evaluator.evaluate(submission(this.context)));
    }

    private List<String> answered()
    {
        return CompletenessFixture.answeredQuestions(this.context);
    }

    private static String path(final String question)
    {
        return VERSION_PATH + "/" + question;
    }

    private static List<String> questionsOf(final List<Resource> answers)
    {
        return answers.stream()
            .map(answer -> answer.adaptTo(Answer.class).getQuestion().getPath())
            .sorted()
            .toList();
    }
}
