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

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Unit tests for {@link ResourceToJsonAdapterFactory}, exercising the whole serialization process with the default
 * processors.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ResourceToJsonAdapterFactoryTest
{
    private ResourceToJsonAdapterFactory factory;

    @BeforeEach
    public void setup()
        throws Exception
    {
        this.factory = new ResourceToJsonAdapterFactory();
        injectProcessors(new PropertiesProcessor(), new IdentificationProcessor(), new DereferenceProcessor(),
            new DeepProcessor());
    }

    @Test
    public void testNullAdaptable()
    {
        Assertions.assertNull(this.factory.getAdapter(null, JsonObject.class));
    }

    @Test
    public void testPropertiesChildrenAndIdentityAreSerialized()
        throws Exception
    {
        final Node child = mockNode("/parent/child", "child");
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockStringProperty("title", "Hello, dashboard!"));
        final NodeIterator children = mockNodeIterator(child);
        Mockito.when(node.getProperties()).thenReturn(properties);
        Mockito.when(node.getNodes()).thenReturn(children);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".deep.json"), JsonObject.class);

        Assertions.assertEquals("Hello, dashboard!", result.getString("title"));
        Assertions.assertEquals("/parent", result.getString("@path"));
        Assertions.assertEquals("parent", result.getString("@name"));
        Assertions.assertEquals("/parent/child", result.getJsonObject("child").getString("@path"));
    }

    @Test
    public void testChildrenAreNotSerializedByDefault()
        throws Exception
    {
        final Node child = mockNode("/parent/child", "child");
        final Node node = mockNode("/parent", "parent");
        final NodeIterator children = mockNodeIterator(child);
        Mockito.when(node.getNodes()).thenReturn(children);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".json"), JsonObject.class);

        // Serializing children requires explicitly enabling the `deep` processor
        Assertions.assertFalse(result.containsKey("child"));
    }

    @Test
    public void testCircularReferencesAreSerializedAsPaths()
        throws Exception
    {
        final Node node = mockNode("/parent", "parent");
        // The node is its own child; instead of infinite recursion, the child must be output as its path
        final NodeIterator children = mockNodeIterator(node);
        Mockito.when(node.getNodes()).thenReturn(children);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".deep.json"), JsonObject.class);

        Assertions.assertEquals("/parent", result.getString("parent"));
    }

    @Test
    public void testReferencesAreDereferenced()
        throws Exception
    {
        final Node target = mockNode("/target", "target");
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockReferenceProperty("reference", target));
        Mockito.when(node.getProperties()).thenReturn(properties);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".json"), JsonObject.class);

        Assertions.assertEquals("/target", result.getJsonObject("reference").getString("@path"));
    }

    @Test
    public void testProcessorsCanBeDisabledWithSelectors()
        throws Exception
    {
        final Node target = mockNode("/target", "target");
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockReferenceProperty("reference", target));
        Mockito.when(node.getProperties()).thenReturn(properties);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".-dereference.json"), JsonObject.class);

        // With the dereference processor disabled, the raw UUID must be output instead of the referenced node
        Assertions.assertEquals("uuid-of-target", result.getString("reference"));
    }

    @Test
    public void testDefaultProcessorsAreUsedWithoutSelectors()
        throws Exception
    {
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockStringProperty("title", "Hello, dashboard!"));
        Mockito.when(node.getProperties()).thenReturn(properties);

        final JsonObject result = this.factory.getAdapter(mockResource(node, null), JsonObject.class);

        Assertions.assertEquals("Hello, dashboard!", result.getString("title"));
        Assertions.assertEquals("/parent", result.getString("@path"));
    }

    @Test
    public void testUnserializableNode()
        throws Exception
    {
        final Node node = mockNode("/parent", "parent");
        Mockito.when(node.getProperties()).thenThrow(new RepositoryException());

        Assertions.assertNull(this.factory.getAdapter(mockResource(node, ".json"), JsonObject.class));
    }

    @Test
    public void testResourceWithoutNode()
    {
        Assertions.assertNull(this.factory.getAdapter(mockResource(null, ".json"), JsonObject.class));
    }

    @Test
    public void testCustomProcessorHooks()
        throws Exception
    {
        // A processor that uses the serializeNode callback from enter, and skips properties by nulling their name
        final ResourceJsonProcessor custom = new ResourceJsonProcessor()
        {
            @Override
            public String getName()
            {
                return "custom";
            }

            @Override
            public int getPriority()
            {
                return 20;
            }

            @Override
            public boolean isEnabledByDefault(final Resource resource)
            {
                return true;
            }

            @Override
            public void enter(final Node node, final JsonObjectBuilder input,
                final Function<Node, JsonValue> serializeNode)
            {
                input.add("fromEnter", serializeNode.apply(node));
            }

            @Override
            public void leave(final Node node, final JsonObjectBuilder json,
                final Function<Node, JsonValue> serializeNode)
            {
                json.add("fromLeave", serializeNode.apply(node));
            }

            @Override
            public String processPropertyName(final Node node, final Property property, final String input)
            {
                return "secret".equals(input) ? null : input;
            }
        };
        injectProcessors(new PropertiesProcessor(), new IdentificationProcessor(), custom);
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockStringProperty("secret", "hidden"));
        Mockito.when(node.getProperties()).thenReturn(properties);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".json"), JsonObject.class);

        // The serializeNode callbacks received in enter() and leave() are usable; since the node is already being
        // serialized, only its path is returned
        Assertions.assertEquals("/parent", result.getString("fromEnter"));
        Assertions.assertEquals("/parent", result.getString("fromLeave"));
        // The property whose name was nulled is skipped
        Assertions.assertFalse(result.containsKey("secret"));
    }

    @Test
    public void testPropertiesCanBeDisabled()
        throws Exception
    {
        final Node node = mockNode("/parent", "parent");
        final PropertyIterator properties = mockPropertyIterator(mockStringProperty("title", "Hello, dashboard!"));
        Mockito.when(node.getProperties()).thenReturn(properties);

        final JsonObject result = this.factory.getAdapter(mockResource(node, ".-properties.json"), JsonObject.class);

        // With the properties processor disabled, no processor serializes property values
        Assertions.assertFalse(result.containsKey("title"));
        Assertions.assertEquals("/parent", result.getString("@path"));
    }

    private void injectProcessors(final ResourceJsonProcessor... processors)
        throws Exception
    {
        final Field allProcessors = ResourceToJsonAdapterFactory.class.getDeclaredField("allProcessors");
        allProcessors.setAccessible(true);
        allProcessors.set(this.factory, List.of(processors));
    }

    private Resource mockResource(final Node node, final String resolutionPathInfo)
    {
        final Resource resource = Mockito.mock(Resource.class);
        final ResourceMetadata metadata = new ResourceMetadata();
        metadata.setResolutionPathInfo(resolutionPathInfo);
        Mockito.when(resource.getResourceMetadata()).thenReturn(metadata);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        return resource;
    }

    private Node mockNode(final String path, final String name)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        // The iterators must be created before stubbing starts, since creating them stubs other mocks,
        // and Mockito doesn't allow interleaved stubbing
        final PropertyIterator properties = mockPropertyIterator();
        final NodeIterator children = mockNodeIterator();
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(node.getName()).thenReturn(name);
        Mockito.when(node.getProperties()).thenReturn(properties);
        Mockito.when(node.getNodes()).thenReturn(children);
        return node;
    }

    private Property mockStringProperty(final String name, final String value)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        final Value jcrValue = Mockito.mock(Value.class);
        Mockito.when(jcrValue.getString()).thenReturn(value);
        Mockito.when(property.getName()).thenReturn(name);
        Mockito.when(property.isMultiple()).thenReturn(false);
        Mockito.when(property.getType()).thenReturn(PropertyType.STRING);
        Mockito.when(property.getValue()).thenReturn(jcrValue);
        return property;
    }

    private Property mockReferenceProperty(final String name, final Node target)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        final Value jcrValue = Mockito.mock(Value.class);
        Mockito.when(jcrValue.getString()).thenReturn("uuid-of-target");
        Mockito.when(property.getName()).thenReturn(name);
        Mockito.when(property.isMultiple()).thenReturn(false);
        Mockito.when(property.getType()).thenReturn(PropertyType.REFERENCE);
        Mockito.when(property.getValue()).thenReturn(jcrValue);
        Mockito.when(property.getNode()).thenReturn(target);
        return property;
    }

    private PropertyIterator mockPropertyIterator(final Property... properties)
    {
        final PropertyIterator iterator = Mockito.mock(PropertyIterator.class);
        final Iterator<Property> backing = List.of(properties).iterator();
        Mockito.when(iterator.hasNext()).thenAnswer(invocation -> backing.hasNext());
        Mockito.when(iterator.nextProperty()).thenAnswer(invocation -> backing.next());
        return iterator;
    }

    private NodeIterator mockNodeIterator(final Node... nodes)
    {
        final NodeIterator iterator = Mockito.mock(NodeIterator.class);
        final Iterator<Node> backing = List.of(nodes).iterator();
        Mockito.when(iterator.hasNext()).thenAnswer(invocation -> backing.hasNext());
        Mockito.when(iterator.nextNode()).thenAnswer(invocation -> backing.next());
        return iterator;
    }
}
