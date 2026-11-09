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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Question;
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

    private Resource submission;

    private Resource question;

    private Resource file;

    @BeforeEach
    void setUp()
    {
        final SubmissionTree tree = new SubmissionTree(this.context);
        tree.createSchemaVersion();
        this.question = tree.createQuestion(AIMS, "Find the primary aims.");
        this.submission = tree.createSubmission();
        this.file = tree.createFile("completed");
    }

    private FieldResult createFoundResult()
    {
        return new FieldResult(AIMS, List.of("Care"), "", 0.9, List.of(new FieldResult.Passage("care", null, "", 0)),
            1);
    }

    private void write(final ResourceResolver resolver) throws PersistenceException
    {
        ExtractedAnswers.write(resolver, this.submission, null, this.question.adaptTo(Question.class),
            createFoundResult(), List.of(this.file.adaptTo(File.class)));
    }

    /** Creates an empty answer to the question, the way the form saves one, with the given extra properties. */
    private Resource createEmptyAnswer(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("jcr:primaryType", "sub:Answer");
        all.put("sling:resourceType", "sub/Answer");
        final Resource answer = this.context.create().resource(this.submission.getPath() + "/empty", all);
        SubmissionTree.reference(answer, "question", this.question);
        return answer;
    }

    private void fill(final ResourceResolver resolver, final Resource existing) throws PersistenceException
    {
        ExtractedAnswers.write(resolver, this.submission, existing, this.question.adaptTo(Question.class),
            createFoundResult(), List.of(this.file.adaptTo(File.class)));
    }

    private boolean hasAnswers()
    {
        for (final Resource child : this.context.resourceResolver().getResource(this.submission.getPath())
            .getChildren()) {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A resolver where the resource at {@code path} adapts to the given node; when {@code parent} is true, its
     * parent does instead.
     */
    private ResourceResolver createSwappingResolver(final String path, final Node node, final boolean parent)
    {
        final ResourceResolver real = this.context.resourceResolver();
        return new ResourceResolverWrapper(real)
        {
            @Override
            public Resource getResource(final String wanted)
            {
                final Resource found = real.getResource(wanted);
                if (found == null || !path.equals(wanted)) {
                    return found;
                }
                return parent ? new ResourceWrapper(found)
                {
                    @Override
                    public Resource getParent()
                    {
                        return mockAdaptingTo(super.getParent(), node);
                    }
                } : mockAdaptingTo(found, node);
            }
        };
    }

    private static Resource mockAdaptingTo(final Resource resource, final Node node)
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
            () -> write(createSwappingResolver(this.question.getPath(), null, false)));
        assertFalse(hasAnswers(), "the half-made answer is taken back down");
    }

    @Test
    void leavesNoAnswerWhenTheRepositoryRefusesTheQuestionReference()
    {
        assertThrows(PersistenceException.class,
            () -> write(createSwappingResolver(this.question.getPath(), createFailingNode(), false)));
        assertFalse(hasAnswers());
    }

    @Test
    void leavesNoAnswerWhenASourceCannotBeReferenced()
    {
        assertThrows(PersistenceException.class, () -> write(createSwappingResolver(this.file.getPath(), null, true)));
        assertFalse(hasAnswers());
    }

    // A suggestion is not an answer until the submitter acts on it, so the answer's value is not changed
    @Test
    void writesTheRunUnderAnEmptyAnswerAndLeavesItsValueAlone() throws Exception
    {
        final Resource existing = createEmptyAnswer(Map.of("value", new String[] { "" }));

        fill(this.context.resourceResolver(), existing);

        assertArrayEquals(new String[] { "" }, existing.getValueMap().get("value", String[].class));
        final Resource run = existing.getChildren().iterator().next();
        assertEquals("Care", run.getValueMap().get("extractedValues", String.class));
    }

    @Test
    void leavesNothingUnderTheAnswerWhenItsRunCannotBeWritten()
    {
        final Resource existing = createEmptyAnswer(Map.of());

        assertThrows(PersistenceException.class,
            () -> fill(createSwappingResolver(this.file.getPath(), null, true), existing));

        assertFalse(existing.getChildren().iterator().hasNext());
        assertNull(existing.getValueMap().get("value", String[].class));
    }

    @Test
    void createsAnAnswerHoldingNoValueForAQuestionWithoutOne() throws Exception
    {
        write(this.context.resourceResolver());

        assertTrue(hasAnswers());
        for (final Resource child : this.context.resourceResolver().getResource(this.submission.getPath())
            .getChildren()) {
            if ("sub:Answer".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                assertNull(child.getValueMap().get("value", String[].class));
                assertTrue(child.getChildren().iterator().hasNext(), "the run goes under it");
            }
        }
    }

    // A run that found nothing still records that these versions were read for the question
    @Test
    void recordsARunThatFoundNothing() throws Exception
    {
        final Resource existing = createEmptyAnswer(Map.of());

        ExtractedAnswers.write(this.context.resourceResolver(), this.submission, existing,
            this.question.adaptTo(Question.class), new FieldResult("aims", List.of(), "Nothing about aims", 0.1,
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
            () -> write(createSwappingResolver(this.file.getPath(), createFailingNode(), true)));
        assertFalse(hasAnswers());
    }
}
