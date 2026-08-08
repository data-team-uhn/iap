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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void viewsContentAsOtherModels()
    {
        final Resource resource = this.context.create().resource("/content/sample",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        // A registered model class yields a new view of the same content
        final Content view = content.as(Content.class);
        assertNotNull(view);
        assertEquals(content.getPath(), view.getPath());
        // A model class the model factory doesn't know about yields nothing
        assertNull(content.as(UnknownModel.class));
    }

    /** A content model that is never registered with the model factory. */
    private static final class UnknownModel extends Content
    {
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
        assertTrue(content.isOfType(Content.RESOURCE_TYPE));
        assertFalse(content.isOfType("iap/SomethingElse"));
    }

    @Test
    void recognizesItsOwnTypeAndItsSupertypes()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            SLING_RESOURCE_TYPE, "iap/Sample",
            "sling:resourceSuperType", Content.RESOURCE_TYPE));
        final Content content = resource.adaptTo(Content.class);

        assertTrue(content.isOfType("iap/Sample"));
        assertTrue(content.isOfType(Content.RESOURCE_TYPE));
        assertFalse(content.isOfType("iap/Unrelated"));
    }

    @Test
    void exposesTheEnclosingContent()
    {
        this.context.create().resource("/content/parent", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource("/content/parent/child",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        final Content parent = content.getParent();

        assertNotNull(parent);
        assertEquals("/content/parent", parent.getPath());
    }

    @Test
    void hasNoParentAtTheRepositoryRoot()
    {
        final Content root = this.context.resourceResolver().getResource("/").adaptTo(Content.class);

        assertNull(root.getParent());
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
    void prefersTheRecordedActorToWhoeverDidTheWriting()
    {
        // Content raised through a workflow is written by the engine's service user, so jcr:createdBy names the
        // machinery; the person it was acting for is recorded separately, and is the honest answer
        final Resource resource = this.context.create().resource("/content/raised", Map.of(
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE,
            "jcr:createdBy", "workflows",
            "createdBy", "alice"));

        assertEquals("alice", resource.adaptTo(Content.class).getCreatedBy());
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
    void convertsArbitraryPropertyToTheRequestedType()
    {
        final Resource resource = this.context.create().resource("/content/sample", Map.of(
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE,
            "customProperty", "customValue"));
        final Content content = resource.adaptTo(Content.class);

        // A single value reads as an array just as well, following the usual ValueMap conversion rules
        assertEquals(List.of("customValue"), List.of(content.get("customProperty", String[].class)));
        assertNull(content.get("missingProperty", String.class));
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
    void listsEveryChildWhenNoTypeIsRequired()
    {
        final Resource parent = this.context.create().resource("/content/parent",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        this.context.create().resource("/content/parent/first", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        // Unlike the type-filtered listing, an unrelated resource type is included too
        this.context.create().resource("/content/parent/unrelated", SLING_RESOURCE_TYPE, "sling:Folder");
        final Content content = parent.adaptTo(Content.class);

        final List<Content> children = content.getChildren(Content.class);

        assertEquals(2, children.size());
        assertEquals("first", children.get(0).getName());
        assertEquals("unrelated", children.get(1).getName());
    }

    @Test
    void listsNoChildrenWhenThereAreNone()
    {
        final Resource parent = this.context.create().resource("/content/childless",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = parent.adaptTo(Content.class);

        assertTrue(content.getChildren(Content.class).isEmpty());
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

    @Test
    void adaptsParentOfTheExpectedType()
    {
        this.context.create().resource("/content/owner", SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource("/content/owner/part",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        final Content owner = content.getParent(Content.RESOURCE_TYPE, Content.class);

        assertNotNull(owner);
        assertEquals("owner", owner.getName());
    }

    @Test
    void returnsNullWhenTheParentIsOfAnotherType()
    {
        // Without the type check the parent would still be adapted, since a resource matching no registered model
        // is handed to whichever implementation comes first rather than rejected
        this.context.create().resource("/content/folder", SLING_RESOURCE_TYPE, "sling:Folder");
        final Resource resource = this.context.create().resource("/content/folder/part",
            SLING_RESOURCE_TYPE, Content.RESOURCE_TYPE);
        final Content content = resource.adaptTo(Content.class);

        assertNull(content.getParent(Content.RESOURCE_TYPE, Content.class));
    }

    @Test
    void returnsNullWhenThereIsNoParent()
    {
        final Content root = this.context.resourceResolver().getResource("/").adaptTo(Content.class);

        assertNotNull(root);
        assertNull(root.getParent(Content.RESOURCE_TYPE, Content.class));
    }
}
