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
package io.uhndata.iap.content.models;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Content}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ContentTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    private Calendar created;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class);
        this.created = Calendar.getInstance();
        this.created.set(2026, Calendar.JANUARY, 15, 10, 30, 0);
    }

    @Test
    void adaptsResourceToModel()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        assertNotNull(resource.adaptTo(Content.class));
    }

    @Test
    void exposesResourceDerivedProperties()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertEquals("/content/sample", content.getPath());
        assertEquals("sample", content.getName());
        assertEquals(Content.RESOURCE_TYPE, content.getType());
    }

    @Test
    void exposesCreationMetadata()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE,
            "jcr:created", this.created,
            "jcr:createdBy", "alice"));
        final Content content = resource.adaptTo(Content.class);

        assertEquals(this.created, content.getCreated());
        assertEquals("alice", content.getCreatedBy());
    }

    @Test
    void toleratesMissingOptionalMetadata()
    {
        // A node without the optional jcr:created / jcr:createdBy still adapts, thanks to the
        // OPTIONAL injection strategy, and the corresponding getters return null.
        final Resource resource = this.context.create().resource("/content/bare",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNotNull(content);
        assertNull(content.getCreated());
        assertNull(content.getCreatedBy());
    }

    @Test
    void exposesArbitraryScalarProperty()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE,
            "customProperty", "customValue"));
        final Content content = resource.adaptTo(Content.class);

        assertEquals("customValue", content.get("customProperty"));
    }

    @Test
    void exposesArbitraryMultiValuedProperty()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE,
            "customProperty", new String[]{ "one", "two" }));
        final Content content = resource.adaptTo(Content.class);

        assertEquals(List.of("one", "two"), List.of((String[]) content.get("customProperty")));
    }

    @Test
    void returnsNullForMissingArbitraryProperty()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.get("missingProperty"));
    }

    @Test
    void exposesJsonRepresentation()
    {
        // The actual serialization is provided by iap-serialization-json's AdapterFactory; here a stand-in
        // adapter is registered to verify that toJson() delegates to it, without depending on that module.
        final JsonObject json = Json.createObjectBuilder().add("path", "/content/sample").build();
        this.context.registerAdapter(Resource.class, JsonObject.class, json);
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertEquals(json, content.toJson());
    }

    @Test
    void returnsNullJsonWhenNoAdapterIsAvailable()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.toJson());
    }

    @Test
    void resolvesReferenceToAdaptedTarget()
        throws RepositoryException
    {
        this.context.create().resource("/content/target", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/content/target");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("11111111-1111-1111-1111-111111111111")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        final Content target = content.getReference("11111111-1111-1111-1111-111111111111", Content.class);

        assertNotNull(target);
        assertEquals("/content/target", target.getPath());
    }

    @Test
    void returnsNullReferenceForNullIdentifier()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.getReference(null, Content.class));
    }

    @Test
    void returnsNullReferenceWhenNoSessionIsAvailable()
    {
        // The default RESOURCERESOLVER_MOCK sling-mock type has no real JCR session behind it.
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.getReference("11111111-1111-1111-1111-111111111111", Content.class));
    }

    @Test
    void returnsNullReferenceWhenIdentifierIsUnresolvable()
        throws RepositoryException
    {
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier(Mockito.anyString())).thenThrow(new RepositoryException());
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.getReference("22222222-2222-2222-2222-222222222222", Content.class));
    }

    @Test
    void returnsNullReferenceWhenTargetPathHasNoResource()
        throws RepositoryException
    {
        // The identifier resolves to a JCR node, but no resource exists at its path (e.g. it was deleted
        // through the JCR API directly, bypassing the resource tree).
        final Node targetNode = Mockito.mock(Node.class);
        Mockito.when(targetNode.getPath()).thenReturn("/content/missing");
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getNodeByIdentifier("33333333-3333-3333-3333-333333333333")).thenReturn(targetNode);
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);

        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.getReference("33333333-3333-3333-3333-333333333333", Content.class));
    }

    @Test
    void listsChildrenOfGivenResourceType()
    {
        final Resource parent = this.context.create().resource("/content/parent",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        this.context.create().resource("/content/parent/first", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        this.context.create().resource("/content/parent/second", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        // A child of an unrelated resource type is excluded, even though it would still adapt
        this.context.create().resource("/content/parent/unrelated", SLING_RESOURCE_TYPE, "sling:Folder");
        final Content content = parent.adaptTo(Content.class);

        final List<Content> children = content.getChildren(Content.RESOURCE_TYPE, Content.class);

        assertEquals(2, children.size());
        assertEquals("first", children.get(0).getName());
        assertEquals("second", children.get(1).getName());
    }

    @Test
    void listsNoChildrenWhenNoneMatch()
    {
        final Resource parent = this.context.create().resource("/content/empty",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        this.context.create().resource("/content/empty/unrelated", SLING_RESOURCE_TYPE, "sling:Folder");
        final Content content = parent.adaptTo(Content.class);

        assertTrue(content.getChildren(Content.RESOURCE_TYPE, Content.class).isEmpty());
    }

    @Test
    void adaptsNamedChild()
    {
        final Resource parent = this.context.create().resource("/content/withChild",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        this.context.create().resource("/content/withChild/single", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = parent.adaptTo(Content.class);

        final Content child = content.getChild("single", Content.class);

        assertNotNull(child);
        assertEquals("single", child.getName());
    }

    @Test
    void returnsNullForMissingNamedChild()
    {
        final Resource parent = this.context.create().resource("/content/withoutChild",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = parent.adaptTo(Content.class);

        assertNull(content.getChild("missing", Content.class));
    }
}
