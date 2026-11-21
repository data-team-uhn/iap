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
package io.uhndata.iap.utils.testing;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Referenceables}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReferenceablesTest
{
    private static final String FIRST = "/content/first";

    private static final String SECOND = "/content/second";

    private static final String UUID = "jcr:uuid";

    private static final String VERSION = "schemaVersion";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private Referenceables referenceables;

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(FIRST);
        this.context.create().resource(SECOND);
        this.referenceables = new Referenceables(this.context.resourceResolver());
    }

    @Test
    void givesEachNodeTheIdentifierTheRepositoryKnowsItBy() throws RepositoryException
    {
        this.referenceables.identify(FIRST, SECOND);

        assertEquals(node(FIRST).getIdentifier(), node(FIRST).getProperty(UUID).getString());
        assertEquals(node(SECOND).getIdentifier(), node(SECOND).getProperty(UUID).getString());
    }

    @Test
    void commitsTheIdentifiers()
    {
        this.referenceables.identify(FIRST);

        assertFalse(this.context.resourceResolver().hasChanges());
    }

    @Test
    void pointsAReferenceAtTheNode() throws RepositoryException
    {
        this.referenceables.setReference(FIRST, VERSION, SECOND);

        assertEquals(PropertyType.REFERENCE, node(FIRST).getProperty(VERSION).getType());
        assertEquals(node(SECOND).getIdentifier(), node(FIRST).getProperty(VERSION).getString());
    }

    @Test
    void commitsTheReference()
    {
        this.referenceables.setReference(FIRST, VERSION, SECOND);

        assertFalse(this.context.resourceResolver().hasChanges());
    }

    @Test
    void namesTheMissingNode()
    {
        final NullPointerException failure = assertThrows(NullPointerException.class,
            () -> this.referenceables.identify("/content/missing"));

        assertTrue(failure.getMessage().contains("/content/missing"));
    }

    @Test
    void reportsANodeThatCannotBeIdentified() throws RepositoryException
    {
        final Node failing = Mockito.mock(Node.class);
        Mockito.when(failing.getIdentifier()).thenThrow(new RepositoryException("unavailable"));

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new Referenceables(resolverServing(failing)).identify(FIRST));

        assertInstanceOf(RepositoryException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(FIRST));
    }

    @Test
    void reportsIdentifiersThatCannotBeCommitted() throws PersistenceException
    {
        final ResourceResolver refusing = resolverServing(Mockito.mock(Node.class));
        Mockito.doThrow(new PersistenceException("refused")).when(refusing).commit();

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new Referenceables(refusing).identify(FIRST));

        assertInstanceOf(PersistenceException.class, failure.getCause());
    }

    @Test
    void reportsAReferenceThatCannotBeSet() throws RepositoryException
    {
        final Node failing = Mockito.mock(Node.class);
        Mockito.when(failing.setProperty(Mockito.eq(VERSION), Mockito.any(Node.class)))
            .thenThrow(new RepositoryException("unavailable"));

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new Referenceables(resolverServing(failing)).setReference(FIRST, VERSION, SECOND));

        assertInstanceOf(RepositoryException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(FIRST + "/" + VERSION));
    }

    @Test
    void reportsAReferenceThatCannotBeCommitted() throws PersistenceException
    {
        final ResourceResolver refusing = resolverServing(Mockito.mock(Node.class));
        Mockito.doThrow(new PersistenceException("refused")).when(refusing).commit();

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new Referenceables(refusing).setReference(FIRST, VERSION, SECOND));

        assertInstanceOf(PersistenceException.class, failure.getCause());
    }

    private Node node(final String path)
    {
        return this.context.resourceResolver().getResource(path).adaptTo(Node.class);
    }

    private static ResourceResolver resolverServing(final Node node)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.adaptTo(Node.class)).thenReturn(node);
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resolver.getResource(Mockito.anyString())).thenReturn(resource);
        return resolver;
    }
}
