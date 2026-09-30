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

import org.apache.sling.api.resource.ModifiableValueMap;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExtractedAnswers}: an answer whose references cannot all be written leaves nothing behind.
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
        tree.schemaVersion();
        this.question = tree.question(AIMS, "Find the primary aims.");
        this.submission = tree.submission();
        this.file = tree.file("completed");
    }

    private FieldResult found()
    {
        return new FieldResult(AIMS, true, 0.9, "Care", "", List.of(new FieldResult.Passage("care", null).in(0)));
    }

    private void write(final ResourceResolver resolver) throws PersistenceException
    {
        ExtractedAnswers.write(resolver, this.submission, null, this.question.adaptTo(Question.class), found(),
            List.of(this.file.adaptTo(File.class)));
    }

    /** An empty answer to the question, as the form saves one, holding the given properties besides. */
    private Resource emptyAnswer(final Map<String, Object> properties)
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
            found(), List.of(this.file.adaptTo(File.class)));
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

    /** A resolver that hands out {@code path} as a resource adapting to the given node. */
    private ResourceResolver swapping(final String path, final Node node, final boolean parent)
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
                        return adaptingTo(super.getParent(), node);
                    }
                } : adaptingTo(found, node);
            }
        };
    }

    private static Resource adaptingTo(final Resource resource, final Node node)
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

    private static Node explosive()
    {
        return Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
    }

    @Test
    void leavesNoAnswerWhenTheQuestionCannotBeReferenced()
    {
        assertThrows(PersistenceException.class, () -> write(swapping(this.question.getPath(), null, false)));
        assertFalse(hasAnswers(), "the half-made answer is taken back down");
    }

    @Test
    void leavesNoAnswerWhenTheRepositoryRefusesTheQuestionReference()
    {
        assertThrows(PersistenceException.class,
            () -> write(swapping(this.question.getPath(), explosive(), false)));
        assertFalse(hasAnswers());
    }

    @Test
    void leavesNoAnswerWhenASourceCannotBeReferenced()
    {
        assertThrows(PersistenceException.class, () -> write(swapping(this.file.getPath(), null, true)));
        assertFalse(hasAnswers());
    }

    @Test
    void fillsAnEmptyAnswer() throws Exception
    {
        final Resource existing = emptyAnswer(Map.of("value", new String[] { "" }));

        fill(this.context.resourceResolver(), existing);

        assertArrayEquals(new String[] { "Care" }, existing.getValueMap().get("value", String[].class));
        assertTrue(existing.getChildren().iterator().hasNext(), "the extraction goes under it");
    }

    @Test
    void putsAnEmptyAnswerBackWhenItsExtractionCannotBeWritten()
    {
        final Resource existing = emptyAnswer(Map.of("value", new String[] { "" }));

        assertThrows(PersistenceException.class,
            () -> fill(swapping(this.file.getPath(), null, true), existing));

        assertArrayEquals(new String[] { "" }, existing.getValueMap().get("value", String[].class));
        assertFalse(existing.getChildren().iterator().hasNext(), "and nothing is left under it");
    }

    @Test
    void putsAValuelessAnswerBackWhenItsExtractionCannotBeWritten()
    {
        final Resource existing = emptyAnswer(Map.of());

        assertThrows(PersistenceException.class,
            () -> fill(swapping(this.file.getPath(), null, true), existing));

        assertNull(existing.getValueMap().get("value", String[].class));
    }

    @Test
    void refusesToFillAnAnswerItCannotWrite()
    {
        final Resource existing = new ResourceWrapper(emptyAnswer(Map.of()))
        {
            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
            }
        };

        assertThrows(PersistenceException.class, () -> fill(this.context.resourceResolver(), existing));
    }

    @Test
    void leavesNoAnswerWhenTheRepositoryRefusesASourceReference()
    {
        assertThrows(PersistenceException.class, () -> write(swapping(this.file.getPath(), explosive(), true)));
        assertFalse(hasAnswers());
    }
}
