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
package io.uhndata.iap.extraction.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractedAnswers}: how a run is saved under an answer, and that a write that fails half
 * way leaves nothing behind.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtractedAnswersTest
{
    private static final String AIMS = "aims";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SubmissionTree tree;

    private Resource submission;

    private Resource question;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        this.tree = new SubmissionTree(this.context);
        this.tree.createSchemaVersion();
        this.question = this.tree.createQuestion(AIMS, "Find the primary aims.");
        this.submission = this.tree.createSubmission();
        this.file = this.tree.createFile("completed");
    }

    private FieldResult createFoundResult()
    {
        return new FieldResult(AIMS, List.of("Care"), "", 0.9, 0.9,
            List.of(new FieldResult.Passage("care", null, "", 0)), 1);
    }

    /** Writes the found result under the existing answer, or under a new one when given {@code null}. */
    private void fill(final ResourceResolver resolver, final Resource existing) throws PersistenceException
    {
        ExtractedAnswers.write(resolver, this.submission, existing, this.question.adaptTo(Question.class),
            createFoundResult(), List.of(this.file.adaptTo(File.class)));
    }

    private List<Resource> getAnswers()
    {
        final List<Resource> answers = new ArrayList<>();
        this.context.resourceResolver().getResource(this.submission.getPath()).getChildren().forEach(child -> {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                answers.add(child);
            }
        });
        return answers;
    }

    /** A resolver where the resource at {@code path} adapts to the given node. */
    private ResourceResolver createResolverSwappingNode(final String path, final Node node)
    {
        return createResolverSwapping(path, found -> wrapAdaptingTo(found, node));
    }

    /** A resolver where the parent of the resource at {@code path} adapts to the given node. */
    private ResourceResolver createResolverSwappingParentNode(final String path, final Node node)
    {
        return createResolverSwapping(path, found -> new ResourceWrapper(found)
        {
            @Override
            public Resource getParent()
            {
                return wrapAdaptingTo(super.getParent(), node);
            }
        });
    }

    private ResourceResolver createResolverSwapping(final String path, final UnaryOperator<Resource> swap)
    {
        final ResourceResolver real = this.context.resourceResolver();
        return new ResourceResolverWrapper(real)
        {
            @Override
            public Resource getResource(final String wanted)
            {
                final Resource found = real.getResource(wanted);
                return found == null || !path.equals(wanted) ? found : swap.apply(found);
            }
        };
    }

    private static Resource wrapAdaptingTo(final Resource resource, final Node node)
    {
        return new ResourceWrapper(resource)
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Node.class ? type.cast(node) : super.adaptTo(type);
            }
        };
    }

    private static Node createFailingNode()
    {
        return Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
    }

    @Test
    void leavesNoAnswerWhenTheQuestionCannotBeReferenced()
    {
        assertThrows(PersistenceException.class,
            () -> fill(createResolverSwappingNode(this.question.getPath(), null), null));
        assertTrue(getAnswers().isEmpty(), "the half-made answer is taken back down");
    }

    @Test
    void leavesNoAnswerWhenTheRepositoryRefusesTheQuestionReference()
    {
        assertThrows(PersistenceException.class,
            () -> fill(createResolverSwappingNode(this.question.getPath(), createFailingNode()), null));
        assertTrue(getAnswers().isEmpty());
    }

    @Test
    void leavesNoAnswerWhenASourceCannotBeReferenced()
    {
        assertThrows(PersistenceException.class,
            () -> fill(createResolverSwappingParentNode(this.file.getPath(), null), null));
        assertTrue(getAnswers().isEmpty());
    }

    // A suggestion is not an answer until the submitter acts on it, so the answer's value is not changed
    @Test
    void writesTheRunUnderAnEmptyAnswerAndLeavesItsValueAlone() throws Exception
    {
        final Resource existing = this.tree.createAnswer(this.question, "");

        fill(this.context.resourceResolver(), existing);

        assertArrayEquals(new String[] { "" }, existing.getValueMap().get("value", String[].class));
        final Resource run = existing.getChildren().iterator().next();
        assertEquals("Care", run.getValueMap().get("extractedValues", String.class));
    }

    @Test
    void leavesNothingUnderTheAnswerWhenItsRunCannotBeWritten()
    {
        final Resource existing = this.tree.createAnswer(this.question);

        assertThrows(PersistenceException.class,
            () -> fill(createResolverSwappingParentNode(this.file.getPath(), null), existing));

        assertFalse(existing.getChildren().iterator().hasNext());
        assertNull(existing.getValueMap().get("value", String[].class));
    }

    @Test
    void createsAnAnswerHoldingNoValueForAQuestionWithoutOne() throws Exception
    {
        fill(this.context.resourceResolver(), null);

        final List<Resource> answers = getAnswers();
        assertEquals(1, answers.size());
        assertNull(answers.get(0).getValueMap().get("value", String[].class));
        assertTrue(answers.get(0).getChildren().iterator().hasNext(), "the run goes under it");
    }

    // The model's own number is kept, so it can be compared with the one lowered for missing quotes
    @Test
    void savesTheModelsConfidenceBesideTheCheckedOne() throws Exception
    {
        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, null,
            this.question.adaptTo(Question.class), new FieldResult(AIMS, List.of("Care"), "", 0.9, 0.45, List.of(), 1),
            List.of(this.file.adaptTo(File.class)));

        final ValueMap run = getAnswers().get(0).getChildren().iterator().next().getValueMap();
        assertEquals(0.9, run.get("modelConfidence", Double.class));
        assertEquals(0.45, run.get("confidence", Double.class));
    }

    // So a change in how answers turn out can be traced to a new model, prompt or template
    @Test
    void savesWhatProducedTheRun() throws Exception
    {
        final FieldResult result = new FieldResult(AIMS, List.of("Care"), "", 0.9, 0.9, List.of(), 1,
            new RunOrigin("asked", "served-2026", "fp_1", "prompt12", "template12", 0.8));

        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, null,
            this.question.adaptTo(Question.class), result, List.of(this.file.adaptTo(File.class)));

        final Extraction run = getAnswers().get(0).getChildren().iterator().next().adaptTo(Extraction.class);
        assertEquals("asked", run.getModel());
        assertEquals("served-2026", run.getServedModel());
        assertEquals("fp_1", run.getSystemFingerprint());
        assertEquals("prompt12", run.getPromptVersion());
        assertEquals("template12", run.getTemplateVersion());
        assertEquals(0.8, run.getConfidenceThreshold());
    }

    // What the provider did not say is left out, rather than written as empty
    @Test
    void leavesOutWhatIsNotKnownOfTheRunsOrigin() throws Exception
    {
        final FieldResult result = new FieldResult(AIMS, List.of("Care"), "", 0.9, 0.9, List.of(), 1,
            new RunOrigin(null, null, null, "prompt12", null, null));

        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, null,
            this.question.adaptTo(Question.class), result, List.of(this.file.adaptTo(File.class)));

        final ValueMap run = getAnswers().get(0).getChildren().iterator().next().getValueMap();
        assertEquals("prompt12", run.get(Extraction.PROMPT_VERSION_PROPERTY, String.class));
        for (final String unknown : List.of(Extraction.MODEL_PROPERTY, Extraction.SERVED_MODEL_PROPERTY,
            Extraction.SYSTEM_FINGERPRINT_PROPERTY, Extraction.TEMPLATE_VERSION_PROPERTY,
            Extraction.CONFIDENCE_THRESHOLD_PROPERTY)) {
            assertFalse(run.containsKey(unknown), unknown);
        }
    }

    // The form shows each quote with where it was found, and a joined read says which document it came from
    @Test
    void savesEachQuoteWithWhereItWasFound() throws Exception
    {
        final Resource consent = this.tree.createFile("completed");
        final FieldResult result = new FieldResult(AIMS, List.of("Care"), "", 0.9, 0.9, List.of(
            new FieldResult.Passage("better care", 3L, "Aims", 0),
            new FieldResult.Passage("you may withdraw", null, "", 1),
            new FieldResult.Passage("from no one document", null, null, -1)), 3);

        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, null,
            this.question.adaptTo(Question.class), result,
            List.of(this.file.adaptTo(File.class), consent.adaptTo(File.class)));

        final List<ValueMap> quotes = new ArrayList<>();
        getAnswers().get(0).getChildren().iterator().next().getChildren()
            .forEach(quote -> quotes.add(quote.getValueMap()));
        assertEquals(List.of("better care", "you may withdraw", "from no one document"),
            quotes.stream().map(quote -> quote.get("quote", String.class)).toList());
        assertEquals("Aims", quotes.get(0).get("header", String.class));
        assertEquals(3L, quotes.get(0).get("page", Long.class));
        assertNull(quotes.get(1).get("header", String.class));
        assertNull(quotes.get(1).get("page", Long.class));
        assertEquals(SubmissionTree.getIdentifier(this.file.getParent()), quotes.get(0).get("source", String.class));
        assertEquals(SubmissionTree.getIdentifier(consent.getParent()), quotes.get(1).get("source", String.class));
        assertNull(quotes.get(2).get("source", String.class));
    }

    // A run that found nothing still records that these versions were read for the question
    @Test
    void recordsARunThatFoundNothing() throws Exception
    {
        final Resource existing = this.tree.createAnswer(this.question);

        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, existing,
            this.question.adaptTo(Question.class), new FieldResult(AIMS, List.of(), "Nothing about aims", 0.1, 0.1,
                List.of(), 0), List.of(this.file.adaptTo(File.class)));

        final Resource run = existing.getChildren().iterator().next();
        assertNull(run.getValueMap().get("extractedValues", String.class));
        assertEquals("Nothing about aims", run.getValueMap().get("reasoning", String.class));
        assertEquals(1, run.getValueMap().get("sources", String[].class).length);
    }

    @Test
    void leavesNoAnswerWhenTheRepositoryRefusesASourceReference()
    {
        assertThrows(PersistenceException.class,
            () -> fill(createResolverSwappingParentNode(this.file.getPath(), createFailingNode()), null));
        assertTrue(getAnswers().isEmpty());
    }
}
