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
        assertNull(answer.getSurestExtraction());
    }

    // The form shows the surest run and the review records a verdict on it, so both have to mean the same run
    @Test
    void namesTheSurestRunAsTheOneToShow()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/surest",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/surest/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "40", "confidence", 0.4));
        this.context.create().resource("/Submissions/submission/surest/extraction1", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "42", "confidence", 0.9));
        this.context.create().resource("/Submissions/submission/surest/extraction2", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "44", "confidence", 0.6));
        final Answer answer = resource.adaptTo(Answer.class);

        assertEquals("42", answer.getSurestExtraction().getExtractedAnswer());
        assertEquals("44", answer.getLatestExtraction().getExtractedAnswer(), "which is not the newest");
    }

    // A run that did not say how sure it was is not sure at all, and ties go to the earlier run so that two
    // readers of this always name the same one
    @Test
    void treatsARunThatSaidNothingAsNotSureAndKeepsTheFirstOfATie()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/tied",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/tied/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "first", "confidence", 0.5));
        this.context.create().resource("/Submissions/submission/tied/extraction1", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "second", "confidence", 0.5));
        this.context.create().resource("/Submissions/submission/tied/extraction2", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "unsaid"));
        final Answer answer = resource.adaptTo(Answer.class);

        assertEquals("first", answer.getSurestExtraction().getExtractedAnswer());
    }

    @Test
    void listsExtractionRunsInTheOrderTheyRan()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/participants",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/participants/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "40"));
        this.context.create().resource("/Submissions/submission/participants/extraction1", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "42"));
        // A file-type answer keeps its upload here too, and that is not an extraction
        this.context.create().resource("/Submissions/submission/participants/upload",
            RESOURCE_TYPE, "nt:file");
        final Answer answer = resource.adaptTo(Answer.class);

        final List<Extraction> extractions = answer.getExtractions();

        assertEquals(2, extractions.size());
        assertEquals("40", extractions.get(0).getExtractedAnswer());
        // The newest run is the last, and it is the one a submitter is shown
        assertEquals("42", answer.getLatestExtraction().getExtractedAnswer());
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
        assertEquals("42", answer.getLatestExtraction().getExtractedAnswer());
        assertNull(answer.getLatestExtraction().getEditDistance());
    }

    @Test
    void readsASingleValuedReplyAsTheOneValueItIs()
    {
        assertEquals(List.of("St Michael's, Sunnybrook"),
            Answer.splitAnswer("St Michael's, Sunnybrook", false));
    }

    // The model is asked for several values as one comma-separated string, so this is how many it gave
    @Test
    void splitsAMultiValuedReplyIntoAValueEach()
    {
        assertEquals(List.of("St Michael's", "Sunnybrook", "Mount Sinai"),
            Answer.splitAnswer("St Michael's,  Sunnybrook , Mount Sinai", true));
    }

    // A model that answered with punctuation only still said something, and storing nothing would lose it
    @Test
    void keepsAReplyThatSplitsIntoNothingWholeInstead()
    {
        assertEquals(List.of(" , , "), Answer.splitAnswer(" , , ", true));
    }

    // What the form compares against the live answer. Comparing the unsplit reply against the stored values
    // reported every multi-valued answer as one the submitter had corrected.
    @Test
    void suggestsTheSameValuesAMultiValuedAnswerIsStoredAs() throws RepositoryException
    {
        final Answer answer = answerFor("participatingSites", 0, "St Michael's, Sunnybrook");

        assertEquals(List.of("St Michael's", "Sunnybrook"), answer.getSuggestedValues());
    }

    @Test
    void suggestsASingleValuedAnswerWhole() throws RepositoryException
    {
        final Answer answer = answerFor("sponsor", 1, "University Health Network, Toronto");

        assertEquals(List.of("University Health Network, Toronto"), answer.getSuggestedValues());
    }

    @Test
    void suggestsNothingWhenNoModelAnsweredAndWhenItNamedNoValue() throws RepositoryException
    {
        final Resource bare = this.context.create().resource("/Submissions/submission/unsuggested",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        assertEquals(List.of(), bare.adaptTo(Answer.class).getSuggestedValues());

        assertEquals(List.of(), answerFor("valueless", 1, null).getSuggestedValues());
    }

    // A question that has gone from the schema leaves its answer behind; it is single-valued as far as anybody
    // can now tell, which is the reading that cannot split something nobody can check
    @Test
    void suggestsWholeWhenTheQuestionCannotBeRead()
    {
        final Resource resource = this.context.create().resource("/Submissions/submission/orphan",
            RESOURCE_TYPE, Answer.RESOURCE_TYPE);
        this.context.create().resource("/Submissions/submission/orphan/extraction0", Map.of(
            RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", "one, two", "confidence", 0.9));

        assertEquals(List.of("one, two"), resource.adaptTo(Answer.class).getSuggestedValues());
    }

    // A model names an option as a person reads it. What is stored, ticked and compared is the option's value,
    // so the reply is matched to the option it names, by label or value and whatever the case.
    @Test
    void suggestsTheOptionAReplyNames() throws RepositoryException
    {
        final Answer answer = answerFor("languages", 0, "English, FRENCH, Klingon");
        option("languages", "english", "English");
        option("languages", "french", "French");

        assertEquals(List.of("english", "french", "Klingon"), answer.getSuggestedValues(),
            "a language the question does not offer is kept as the model said it");
    }

    private void option(final String question, final String value, final String label)
    {
        this.context.create().resource("/Schemas/schema/1.0/" + question + "/" + value, Map.of(
            RESOURCE_TYPE, AnswerOption.RESOURCE_TYPE, "value", value, "label", label));
    }

    /**
     * An answer to a question of the given cardinality, carrying one extraction run.
     *
     * @param name what to call the question and the answer
     * @param maxAnswers the most values the question takes; 0 for any number
     * @param suggested what the model answered, or {@code null} for a run that named nothing
     * @return the answer
     */
    private Answer answerFor(final String name, final long maxAnswers, final String suggested)
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
        final Map<String, Object> run = suggested == null
            ? Map.of(RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "confidence", 0.9)
            : Map.of(RESOURCE_TYPE, Extraction.RESOURCE_TYPE, "extractedAnswer", suggested,
                "confidence", 0.9);
        this.context.create().resource("/Submissions/submission/" + name + "/extraction0", run);
        return resource.adaptTo(Answer.class);
    }
}
