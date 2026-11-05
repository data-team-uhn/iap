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

package io.uhndata.iap.serialization.internal;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link OriginalCreatedProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class OriginalCreatedProcessorTest
{
    private static final String CREATED = "jcr:created";

    private static final String ORIGINAL = OriginalCreatedProcessor.ORIGINAL_CREATED_PROPERTY;

    private final OriginalCreatedProcessor processor = new OriginalCreatedProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("originalCreated", this.processor.getName());
        Assertions.assertEquals(30, this.processor.getPriority());
        Assertions.assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    public void testTheOriginalDateIsServedAsTheCreationDate() throws RepositoryException
    {
        final Node node = node(true);

        Assertions.assertEquals(CREATED, this.processor.processPropertyName(node, property(ORIGINAL), ORIGINAL));
        Assertions.assertNull(this.processor.processPropertyName(node, property(CREATED), CREATED),
            "The arrival is left out");
    }

    @Test
    public void testContentCreatedHereIsLeftAlone() throws RepositoryException
    {
        final Node node = node(false);

        Assertions.assertEquals(CREATED, this.processor.processPropertyName(node, property(CREATED), CREATED));
        Assertions.assertEquals("title", this.processor.processPropertyName(node, property("title"), "title"));
    }

    @Test
    public void testWhatAnotherProcessorDroppedStaysDropped() throws RepositoryException
    {
        Assertions.assertNull(this.processor.processPropertyName(node(true), property(ORIGINAL), null));
    }

    @Test
    public void testAPropertyThatIsNotThereChangesNothing() throws RepositoryException
    {
        Assertions.assertEquals("title", this.processor.processPropertyName(node(true), null, "title"));
    }

    @Test
    public void testANodeThatCannotBeAskedIsLeftAlone() throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.hasProperty(ORIGINAL)).thenThrow(new RepositoryException("boom"));

        Assertions.assertEquals(CREATED, this.processor.processPropertyName(node, property(CREATED), CREATED),
            "A serialization must not fail over a question about the content it is serializing");
    }

    private static Node node(final boolean transferred) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.hasProperty(ORIGINAL)).thenReturn(transferred);
        return node;
    }

    private static Property property(final String name) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn(name);
        return property;
    }
}
