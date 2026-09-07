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
package io.uhndata.iap.workflows.internal;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.version.OnParentVersionAction;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EntityRevision}: which node is marked, and that the mark differs every time. Oak-backed, so
 * that the entity node type is the one the module declares. {@link EntityRevisionRaceTest} shows the refusal.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityRevisionTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String ENTITY = "/Things/one";

    private static final String DEFINITION = "/Definitions/one";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void marksTheNearestEnclosingEntityRatherThanTheOutermost() throws WorkflowFailedException
    {
        create("/Things/outer", Entity.RESOURCE_TYPE);
        create("/Things/outer/inner", Entity.RESOURCE_TYPE);

        EntityRevision.stamp(create("/Things/outer/inner/part", "sub/Answer"));

        assertNotNull(revisionOf("/Things/outer/inner"));
        assertNull(revisionOf("/Things/outer"));
        assertNull(revisionOf("/Things/outer/inner/part"));
    }

    @Test
    void leavesAChangeThatIsNotAboutAnEntityAlone() throws WorkflowFailedException
    {
        EntityRevision.stamp(create("/Config/thing", "app/Configuration"));

        assertNull(revisionOf("/Config/thing"));
    }

    @Test
    void marksItDifferentlyEveryTime() throws WorkflowFailedException
    {
        final Resource entity = create(ENTITY, Entity.RESOURCE_TYPE);

        EntityRevision.stamp(entity);
        final String first = revisionOf(ENTITY);
        EntityRevision.stamp(entity);

        assertNotNull(first);
        assertNotEquals(first, revisionOf(ENTITY));
    }

    @Test
    void marksAnEntityThatIsCheckedIn() throws Exception
    {
        final Node definition = createDefinition();
        session().getWorkspace().getVersionManager().checkin(DEFINITION);

        EntityRevision.stamp(resolver().getResource(DEFINITION));
        resolver().commit();

        assertNotNull(revisionOf(DEFINITION));
        assertFalse(definition.isCheckedOut());
    }

    @Test
    void declaresTheTokenOutsideTheEntitysContent() throws Exception
    {
        final Node definition = createDefinition();

        EntityRevision.stamp(resolver().getResource(DEFINITION));

        final PropertyDefinition declared = definition.getProperty(Entity.REVISION_PROPERTY).getDefinition();
        assertEquals("data:Entity", declared.getDeclaringNodeType().getName());
        assertEquals(OnParentVersionAction.IGNORE, declared.getOnParentVersion());
    }

    @Test
    void leavesAnEntityThisSessionRemovedAlone() throws PersistenceException
    {
        final Resource entity = create(ENTITY, Entity.RESOURCE_TYPE);
        resolver().commit();
        resolver().delete(entity);

        assertDoesNotThrow(() -> EntityRevision.stamp(entity));
    }

    @Test
    void refusesAnEntityItCannotWriteTo()
    {
        final Resource unwritable = Mockito.mock(Resource.class);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(unwritable.isResourceType(Entity.RESOURCE_TYPE)).thenReturn(true);
        Mockito.when(unwritable.getPath()).thenReturn(ENTITY);
        Mockito.when(unwritable.getResourceResolver()).thenReturn(resolver);
        Mockito.when(resolver.getResource(ENTITY)).thenReturn(unwritable);

        final WorkflowFailedException refusal =
            assertThrows(WorkflowFailedException.class, () -> EntityRevision.stamp(unwritable));
        assertTrue(refusal.getMessage().contains(ENTITY));
        Mockito.verify(unwritable).adaptTo(ModifiableValueMap.class);
    }

    private Resource create(final String path, final String resourceType)
    {
        return this.context.create().resource(path, Map.of(TYPE, resourceType));
    }

    /**
     * Creates and saves a workflow definition, an entity of a node type the module declares.
     *
     * @return its node
     * @throws RepositoryException when it cannot be saved
     */
    private Node createDefinition() throws RepositoryException
    {
        final Node definition = session().getRootNode().addNode("Definitions", "nt:unstructured")
            .addNode("one", "wf:WorkflowDefinition");
        definition.setProperty("title", "One");
        session().save();
        return definition;
    }

    private String revisionOf(final String path)
    {
        return resolver().getResource(path).getValueMap().get(Entity.REVISION_PROPERTY, String.class);
    }

    private ResourceResolver resolver()
    {
        return this.context.resourceResolver();
    }

    private Session session()
    {
        return resolver().adaptTo(Session.class);
    }
}
