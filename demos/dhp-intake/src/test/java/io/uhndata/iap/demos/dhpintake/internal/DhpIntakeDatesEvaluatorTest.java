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
package io.uhndata.iap.demos.dhpintake.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

import static io.uhndata.iap.demos.dhpintake.internal.DhpIntakeDatesEvaluator.END_DATE_QUESTION;
import static io.uhndata.iap.demos.dhpintake.internal.DhpIntakeDatesEvaluator.FREQUENCY_QUESTION;
import static io.uhndata.iap.demos.dhpintake.internal.DhpIntakeDatesEvaluator.START_DATE_QUESTION;
import static io.uhndata.iap.demos.dhpintake.internal.DhpIntakeDatesEvaluator.STOP_MONTH_QUESTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DhpIntakeDatesEvaluator}: which intake requests it holds back, and the answers it points at.
 * The context is JCR-backed, since a request names its schema version, and an answer its question, by
 * {@code jcr:uuid}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DhpIntakeDatesEvaluatorTest
{
    private static final String VERSION_PATH = "/Schemas/dhpIntake/v1";

    private static final String OTHER_VERSION_PATH = "/Schemas/somethingElse/v1";

    private static final String REQUEST_PATH = "/Submissions/aRequest";

    private static final String TYPE = "sling:resourceType";

    private static final String PAST_DAY = "2020-01-01";

    private static final String FUTURE_DAY = LocalDate.now().plusDays(1).toString();

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DhpIntakeDatesEvaluator evaluator = new DhpIntakeDatesEvaluator();

    @BeforeEach
    void setUp()
    {
        for (final String version : List.of(VERSION_PATH, OTHER_VERSION_PATH)) {
            this.context.create().resource(version, TYPE, SchemaVersion.RESOURCE_TYPE);
        }
        for (final String question : List.of(START_DATE_QUESTION, END_DATE_QUESTION, FREQUENCY_QUESTION,
            STOP_MONTH_QUESTION)) {
            this.context.create().resource(VERSION_PATH + "/" + question, TYPE, Question.RESOURCE_TYPE);
        }
    }

    @Test
    void passesDatesInOrderUpToToday()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, START_DATE_QUESTION, PAST_DAY);
        answer(request, END_DATE_QUESTION, LocalDate.now().toString());

        assertEquals(List.of(), flagged(request));
    }

    @Test
    void passesAStartDateOnItsOwn()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, START_DATE_QUESTION, PAST_DAY);

        assertEquals(List.of(), flagged(request));
    }

    @Test
    void pointsAtAStartDateInTheFuture()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, START_DATE_QUESTION, FUTURE_DAY);

        assertEquals(List.of(REQUEST_PATH + "/startDate"), flagged(request));
    }

    @Test
    void pointsAtAnEndDateBeforeTheStartDate()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, START_DATE_QUESTION, "2024-06-01");
        answer(request, END_DATE_QUESTION, "2024-05-31");

        assertEquals(List.of(REQUEST_PATH + "/endDate"), flagged(request));
    }

    @Test
    void pointsAtAStopMonthAlreadyOver()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, FREQUENCY_QUESTION, "repeating");
        answer(request, STOP_MONTH_QUESTION, "2020-01");

        assertEquals(List.of(REQUEST_PATH + "/stopMonth"), flagged(request));
    }

    @Test
    void passesAStopMonthThatIsTheCurrentMonth()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, FREQUENCY_QUESTION, "repeating");
        answer(request, STOP_MONTH_QUESTION, YearMonth.now().toString());

        assertEquals(List.of(), flagged(request));
    }

    @Test
    void passesARepeatingRequestStillMissingItsStopMonth()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, FREQUENCY_QUESTION, "repeating");

        assertEquals(List.of(), flagged(request));
    }

    // Saving refuses it already, by the question's pattern, so it is not this rule's to judge
    @Test
    void passesAStopMonthThatIsNotAMonth()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, FREQUENCY_QUESTION, "repeating");
        answer(request, STOP_MONTH_QUESTION, "2020-13");

        assertEquals(List.of(), flagged(request));
    }

    // The stop month is no longer asked, so what it still holds does not count
    @Test
    void passesAStopMonthLeftOverOnceTheRequestIsForOneTime()
    {
        final Resource request = request(VERSION_PATH);
        answer(request, FREQUENCY_QUESTION, "once");
        answer(request, STOP_MONTH_QUESTION, "2020-01");

        assertEquals(List.of(), flagged(request));
    }

    @Test
    void passesARequestAnsweringASchemaWithoutTheseQuestions()
    {
        final String otherStartDate = OTHER_VERSION_PATH + "/details/startDate";
        this.context.create().resource(otherStartDate, TYPE, Question.RESOURCE_TYPE);
        final Resource request = request(OTHER_VERSION_PATH);
        this.context.create().resource(REQUEST_PATH + "/startDate", TYPE, Answer.RESOURCE_TYPE,
            "question", identifierOf(otherStartDate), "value", new String[] {FUTURE_DAY});

        assertEquals(List.of(), flagged(request), "A schema without the intake's questions is never judged");
    }

    private List<String> flagged(final Resource request)
    {
        return this.evaluator.evaluate(request).stream().map(Resource::getPath).toList();
    }

    private Resource request(final String versionPath)
    {
        return this.context.create().resource(REQUEST_PATH, Map.of(TYPE, Submission.RESOURCE_TYPE,
            "schemaVersion", identifierOf(versionPath)));
    }

    private void answer(final Resource request, final String question, final String value)
    {
        this.context.create().resource(request.getPath() + "/" + question.substring(question.lastIndexOf('/') + 1),
            Map.of(TYPE, Answer.RESOURCE_TYPE, "question", identifierOf(VERSION_PATH + "/" + question),
                "value", new String[] {value}));
    }

    private String identifierOf(final String path)
    {
        try {
            return Objects.requireNonNull(Objects.requireNonNull(this.context.resourceResolver().getResource(path))
                .adaptTo(Node.class)).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }
}
