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

package io.uhndata.iap.utils;

import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link PrefixTree}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PrefixTreeTest
{
    private static final String NAME = "abcdef01-2345-6789-abcd-ef0123456789";

    private static final String TYPE = "nt:unstructured";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Session session;

    private Node root;

    @BeforeEach
    void setup() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        this.root = this.session.getRootNode().addNode("content", TYPE);
        this.session.save();
    }

    @Test
    void namesAreFiledUnderOneBucketPerLevel() throws Exception
    {
        final Node bucket = PrefixTree.bucketFor(this.root, NAME, TYPE);
        assertEquals("/content/ab/cd/ef", bucket.getPath());
        assertEquals(TYPE, bucket.getPrimaryNodeType().getName());
    }

    @Test
    void bucketsAreSharedByNamesWithACommonPrefix() throws Exception
    {
        final Node bucket = PrefixTree.bucketFor(this.root, NAME, TYPE);
        // Only the first characters matter, so a name sharing them reuses the whole chain...
        assertEquals(bucket.getPath(),
            PrefixTree.bucketFor(this.root, "abcdef99-9999-9999-9999-999999999999", TYPE).getPath());
        // ...and one diverging earlier branches off at the level where the names differ
        assertEquals("/content/ab/cd/00",
            PrefixTree.bucketFor(this.root, "abcd0000-0000-0000-0000-000000000000", TYPE).getPath());
        assertEquals(1, this.root.getNodes().getSize());
        assertEquals(2, this.session.getNode("/content/ab/cd").getNodes().getSize());
    }

    @Test
    void bucketsCreatedConcurrentlyAreAdopted() throws Exception
    {
        final boolean[] raced = new boolean[1];
        final Session racing = mock(Session.class, delegatesTo(this.session));
        doAnswer(invocation -> {
            this.session.save();
            raced[0] = true;
            // The bucket is in place, but this session is told its own commit did not go through, which is what
            // a session racing another one into the same bucket sees
            throw new InvalidItemStateException();
        }).when(racing).save();
        doNothing().when(racing).refresh(anyBoolean());
        final Node racingRoot = mock(Node.class, delegatesTo(this.root));
        doReturn(racing).when(racingRoot).getSession();

        assertEquals("/content/ab/cd/ef", PrefixTree.bucketFor(racingRoot, NAME, TYPE).getPath());
        assertTrue(raced[0]);
    }

    @Test
    void pathsCanBeComputedWithoutTheRepository() throws Exception
    {
        assertEquals(PrefixTree.bucketFor(this.root, NAME, TYPE).getPath() + "/" + NAME,
            PrefixTree.pathFor("/content", NAME));
        // A tree rooted at the repository root does not grow a double slash
        assertEquals("/ab/cd/ef/" + NAME, PrefixTree.pathFor("/", NAME));
    }

    @Test
    void namesTooShortToFileAreRejected()
    {
        final String tooShort = "abcde";
        assertThrows(IllegalArgumentException.class, () -> PrefixTree.bucketFor(this.root, tooShort, TYPE));
        assertThrows(IllegalArgumentException.class, () -> PrefixTree.pathFor("/content", tooShort));
    }
}
