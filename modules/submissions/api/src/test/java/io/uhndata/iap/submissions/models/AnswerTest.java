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

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Answer}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerTest
{
    private static final String RESOURCE_TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, Answer.class, Question.class,
            Extraction.class, AnswerOption.class);
    }

    @Test
    void exposesItsIdentifier()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/identified", Map.of(
            "sling:resourceType", Answer.RESOURCE_TYPE,
            "jcr:uuid", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345"));

        assertEquals("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345", resource.adaptTo(Answer.class).getIdentifier());
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/answer",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Answer.class));
    }

    @Test
    void exposesAnswerProperties()
        throws RepositoryException
    {
        this.context.create().resource("/Schemas/schema/1.0/q1",
            RESOURCE_TYPE, Question.RESOURCE_TYPE, "text", "Does this involve human subjects?");
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/Schemas/schema/1.0/q1");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission/answer", Map.of(
            RESOURCE_TYPE, Answer.RESOURCE_TYPE,
            "question", "6f1c1e6a-9d2b-4a7e-8c3f-abcdef012345",
            "value", new String[]{ "yes" }));
        final Answer answer = resource.adaptTo(Answer.class);

        assertEquals("Does this involve human subjects?", answer.getQuestion().getText());
        assertArrayEquals(new String[]{ "yes" }, answer.getValue());
    }

    @Test
    void toleratesMissingOptionalProperties()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/bare",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        final Answer answer = resource.adaptTo(Answer.class);

        assertNotNull(answer);
        assertNull(answer.getQuestion());
        assertNull(answer.getValue());
        assertTrue(answer.getExtractions().isEmpty());
        assertNull(answer.getLatestExtraction());
    }

    // Only the newest run is shown, even when an older run was surer
    @Test
    void showsTheNewestRunWhateverTheOlderRunsSaid()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/newest",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/newest/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "40", "confidence", 0.4));
        this.context.create().resource("/Submissions/submission/newest/extraction1", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "42", "confidence", 0.9));
        this.context.create().resource("/Submissions/submission/newest/extraction2", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "44", "confidence", 0.6));
        final Answer answer = resource.adaptTo(Answer.class);

        assertEquals(List.of("44"), answer.getLatestExtraction().getExtractedValues());
        assertEquals(List.of("44"), answer.getSuggestedValues());
    }

    @Test
    void suggestsNothingWhenTheNewestRunFoundNothing()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/emptied",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/emptied/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "42", "confidence", 0.9));
        this.context.create().resource("/Submissions/submission/emptied/extraction1", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "confidence", 0.3));

        assertEquals(List.of(), resource.adaptTo(Answer.class).getSuggestedValues());
    }

    @Test
    void listsExtractionRunsInTheOrderTheyRan()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/participants",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/participants/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "40"));
        this.context.create().resource("/Submissions/submission/participants/extraction1", Map.of(
            "sling:resourceType", Extraction.RESOURCE_TYPE, "extractedAnswer", "42"));
        // An answer may hold other children too, and those are not extractions
        this.context.create().resource("/Submissions/submission/participants/upload",
            RESOURCE_TYPE, "nt:file");
        final Answer answer = resource.adaptTo(Answer.class);

        final List<Extraction> extractions = answer.getExtractions();

        assertEquals(2, extractions.size());
        assertEquals(List.of("40"), extractions.get(0).getExtractedValues());
        // The newest run is the last, and it is the one a submitter is shown
        assertEquals(List.of("42"), answer.getLatestExtraction().getExtractedValues());
    }

    @Test
    void keepsAnUnapprovedExtractionOutOfTheValue()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/participants",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/participants/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "42"));
        final Answer answer = resource.adaptTo(Answer.class);

        // The submitter has not accepted it, so the answer is still unanswered
        assertNull(answer.getValue());
        assertEquals(List.of("42"), answer.getLatestExtraction().getExtractedValues());
        assertNull(answer.getLatestExtraction().getEditDistance());
    }

    // A step that routes on a reading before anyone checks it sees the suggestion; a value the submitter saves wins
    @Test
    void standsForTheSuggestionUntilTheSubmitterGivesAValue() throws RepositoryException
    {
        final Answer unanswered = createAnswer("decision", 1, "yes");
        assertEquals(List.of("yes"), unanswered.getValueOrSuggestion());

        this.context.resourceResolver().getResource(unanswered.getPath()).adaptTo(ModifiableValueMap.class)
            .put("value", new String[] { "   " });
        assertEquals(List.of("yes"), readAnswer(unanswered).getValueOrSuggestion(), "a blank value gives nothing");

        this.context.resourceResolver().getResource(unanswered.getPath()).adaptTo(ModifiableValueMap.class)
            .put("value", new String[] { "no" });
        assertEquals(List.of("no"), readAnswer(unanswered).getValueOrSuggestion());
    }

    // Saved one each, so a value holding a comma stays one value
    @Test
    void suggestsTheValuesARunSavedAsTheyAre() throws RepositoryException
    {
        final Answer answer = createAnswer("participatingSites", 0, "Sunnybrook Health Sciences Centre, Toronto",
            "St Michael's");

        assertEquals(List.of("Sunnybrook Health Sciences Centre, Toronto", "St Michael's"),
            answer.getSuggestedValues());
    }

    @Test
    void suggestsASingleValuedAnswerWhole() throws RepositoryException
    {
        final Answer answer = createAnswer("sponsor", 1, "University Health Network, Toronto");

        assertEquals(List.of("University Health Network, Toronto"), answer.getSuggestedValues());
    }

    @Test
    void suggestsNothingWhenNoModelAnsweredAndWhenItNamedNoValue() throws RepositoryException
    {
        final Resource bare = this.context.create().resource("/Submissions/submission/unsuggested",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        assertEquals(List.of(), bare.adaptTo(Answer.class).getSuggestedValues());

        assertEquals(List.of(), createAnswer("valueless", 1).getSuggestedValues());
    }

    // With no answer yet, the newest run is the suggestion, whatever the runs before it said
    @Test
    void offersTheNewestSuggestionWhenThereIsNoAnswer() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1, "24 months");
        addRun(answer, "24 months");

        assertEquals(List.of("24 months"), readAnswer(answer).getOfferedSuggestion());
    }

    // A newer document says something new: the answer stays, and the new suggestion is shown beside it
    @Test
    void offersANewSuggestionThatDiffersFromTheAnswerAndTheRunBefore() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1, "24 months");
        setValue(answer, "36 months");
        addRun(answer, "30 months");

        final Answer read = readAnswer(answer);
        assertEquals(List.of("30 months"), read.getOfferedSuggestion());
        assertEquals(List.of("36 months"), read.getValueOrSuggestion(), "the answer itself is kept");
    }

    // A suggestion the answer already holds offers nothing to choose
    @Test
    void offersNothingWhenTheNewSuggestionIsTheAnswer() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1, "24 months");
        setValue(answer, " 36 Months ");
        addRun(answer, "36 months");

        assertEquals(List.of(), readAnswer(answer).getOfferedSuggestion());
    }

    // The submitter saw that suggestion before and kept their own answer, so it is not offered again
    @Test
    void offersNothingWhenTheNewSuggestionRepeatsTheRunBefore() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1, "24 months");
        setValue(answer, "36 months");
        addRun(answer, "24 months");

        assertEquals(List.of(), readAnswer(answer).getOfferedSuggestion());
    }

    // Several values say the same thing in any order
    @Test
    void comparesSeveralValuesAsASet() throws RepositoryException
    {
        final Answer answer = createAnswer("sites", 0, "Sunnybrook", "St Michael's");
        setValue(answer, "St Michael's", "Sunnybrook");

        assertEquals(List.of(), readAnswer(answer).getOfferedSuggestion(), "the only run, equal to the answer");
        addRun(answer, "St Michael's", "Mount Sinai");
        assertEquals(List.of("St Michael's", "Mount Sinai"), readAnswer(answer).getOfferedSuggestion());
    }

    // A first run with a value already saved has no run before it, so only the answer is compared
    @Test
    void offersTheOnlyRunWhenItDiffersFromTheAnswer() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1, "24 months");
        setValue(answer, "36 months");

        assertEquals(List.of("24 months"), readAnswer(answer).getOfferedSuggestion());
    }

    // A run before that found nothing has nothing to repeat; one that finds nothing has nothing to offer
    @Test
    void comparesWithARunBeforeThatFoundNothingAndOffersNothingFoundAsNothing() throws RepositoryException
    {
        final Answer answer = createAnswer("duration", 1);
        setValue(answer, "36 months");
        addRun(answer, "30 months");
        assertEquals(List.of("30 months"), readAnswer(answer).getOfferedSuggestion());

        addRun(answer);
        assertEquals(List.of(), readAnswer(answer).getOfferedSuggestion());
    }

    private void setValue(final Answer answer, final String... values)
    {
        this.context.resourceResolver().getResource(answer.getPath()).adaptTo(ModifiableValueMap.class)
            .put("value", values);
    }

    /** Adds a newer run under an answer, with the given suggested values, or none. */
    private void addRun(final Answer answer, final String... suggested)
    {
        final int runs = readAnswer(answer).getExtractions().size();
        this.context.create().resource(answer.getPath() + "/extraction" + runs, createRun(suggested));
    }

    private static Map<String, Object> createRun(final String... suggested)
    {
        return suggested.length == 0 ? Map.of(RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "confidence", 0.9)
            : Map.of(RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", suggested, "confidence", 0.9);
    }

    /**
     * Creates an answer to a question of the given cardinality, with one extraction run under it.
     *
     * @param name what to call the question and the answer
     * @param maxAnswers the most values the question takes; 0 for any number
     * @param suggested the values the model answered, none for a run that named nothing
     * @return the answer
     */
    private Answer createAnswer(final String name, final long maxAnswers, final String... suggested)
        throws RepositoryException
    {
        final String questionPath = "/Schemas/schema/1.0/" + name;
        this.context.create().resource(questionPath, Map.of(
            RESOURCE_TYPE, Question.RESOURCE_TYPE, "text", name, "maxAnswers", maxAnswers));
        final Node questionNode = Mockito.mock(Node.class);
        Mockito.when(questionNode.getPath()).thenReturn(questionPath);
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(name)).thenReturn(questionNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/Submissions/submission/" + name, Map.of(
            RESOURCE_TYPE, Answer.RESOURCE_TYPE, "question", name));
        this.context.create().resource("/Submissions/submission/" + name + "/extraction0", createRun(suggested));
        return resource.adaptTo(Answer.class);
    }

    /** Adapts the answer again, since a model keeps the values it had when it was adapted. */
    private Answer readAnswer(final Answer answer)
    {
        return this.context.resourceResolver().getResource(answer.getPath()).adaptTo(Answer.class);
    }
}
