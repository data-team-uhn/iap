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
 * Unit tests for {@link FrozenNodeProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class FrozenNodeProcessorTest
{
    private final FrozenNodeProcessor processor = new FrozenNodeProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("frozen", this.processor.getName());
        // After `simple`, whose dropping of the frozen properties this undoes
        Assertions.assertEquals(30, this.processor.getPriority());
    }

    @Test
    public void testTheOriginalsTypeAndIdentityAreServedAsTheTypeAndIdentity() throws RepositoryException
    {
        final Node node = frozenNode();
        Assertions.assertEquals("jcr:primaryType",
            this.processor.processPropertyName(node, property("jcr:frozenPrimaryType"), null));
        Assertions.assertEquals("jcr:uuid",
            this.processor.processPropertyName(node, property("jcr:frozenUuid"), null));
    }

    @Test
    public void testTheCopysOwnBookkeepingIsLeftOut() throws RepositoryException
    {
        final Node node = frozenNode();
        // Both would otherwise describe the copy: a type nothing has heard of, and an identity nobody asked about
        Assertions.assertNull(this.processor.processPropertyName(node, property("jcr:primaryType"), "jcr:primaryType"));
        Assertions.assertNull(this.processor.processPropertyName(node, property("jcr:uuid"), "jcr:uuid"));
        Assertions.assertNull(
            this.processor.processPropertyName(node, property("jcr:frozenMixinTypes"), "jcr:frozenMixinTypes"));
    }

    @Test
    public void testContentPropertiesAreLeftAlone() throws RepositoryException
    {
        final Node node = frozenNode();
        Assertions.assertEquals("title", this.processor.processPropertyName(node, property("title"), "title"));
        Assertions.assertNull(this.processor.processPropertyName(node, property("status"), null),
            "A property another processor has already dropped stays dropped");
    }

    @Test
    public void testALiveResourceIsNotTouched() throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(FrozenNodeProcessor.FROZEN_NODE)).thenReturn(false);

        Assertions.assertEquals("jcr:primaryType",
            this.processor.processPropertyName(node, property("jcr:primaryType"), "jcr:primaryType"));
    }

    @Test
    public void testItRunsUnaskedOnAPastStateAndOnlyThere()
    {
        Assertions.assertTrue(this.processor.isEnabledByDefault(frozenResource(true)),
            "A past state that does not announce itself is the mistake a history view must not make");
        Assertions.assertFalse(this.processor.isEnabledByDefault(frozenResource(false)));
    }

    @Test
    public void testAResourceThatIsNotANodeIsNotFrozen()
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(null);

        Assertions.assertFalse(this.processor.isEnabledByDefault(resource));
    }

    @Test
    public void testAPropertyThatIsNotThereChangesNothing()
    {
        Assertions.assertEquals("title", this.processor.processPropertyName(frozenNode(), null, "title"));
    }

    @Test
    public void testANodeThatCannotBeAskedIsLeftAlone() throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(Mockito.anyString())).thenThrow(new RepositoryException("boom"));

        Assertions.assertEquals("jcr:primaryType",
            this.processor.processPropertyName(node, property("jcr:primaryType"), "jcr:primaryType"),
            "A serialization must not fail over a question about the content it is serializing");
    }

    @Test
    public void testAPropertyThatCannotBeNamedIsLeftAlone() throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenThrow(new RepositoryException("boom"));

        Assertions.assertEquals("title", this.processor.processPropertyName(frozenNode(), property, "title"));
    }

    private Node frozenNode()
    {
        try {
            final Node node = Mockito.mock(Node.class);
            Mockito.when(node.isNodeType(FrozenNodeProcessor.FROZEN_NODE)).thenReturn(true);
            return node;
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private Resource frozenResource(final boolean frozen)
    {
        try {
            final Node node = Mockito.mock(Node.class);
            Mockito.when(node.isNodeType(FrozenNodeProcessor.FROZEN_NODE)).thenReturn(frozen);
            final Resource resource = Mockito.mock(Resource.class);
            Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
            return resource;
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private Property property(final String name) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn(name);
        return property;
    }
}
