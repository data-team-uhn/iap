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
package io.uhndata.iap.uix;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.script.SimpleBindings;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExtensionsManager}: which extensions it finds for a point, in what order, and how it
 * hands them to a script.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ExtensionsManagerTest
{
    private static final String VIEW_POINT = "iap/coreUI/view";

    private static final String QUOTED_POINT = "iap/coreUI/reviewer's view";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    private Node extensions;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        // Registered by hand: on a clean build the manifest declaring them does not exist yet
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/extension.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.extensions = this.session.getRootNode().addNode("Extensions", "nt:unstructured");
    }

    @Test
    void findsTheExtensionsOfAPointWhoseIdHoldsAQuote() throws RepositoryException
    {
        extension("reviewers", QUOTED_POINT, 0);
        extension("everyone", VIEW_POINT, 0);

        assertEquals(List.of("/Extensions/reviewers"), paths(managerFor(QUOTED_POINT).listAll()));
    }

    @Test
    void listsTheExtensionsInTheirDefaultOrder() throws RepositoryException
    {
        extension("second", VIEW_POINT, 2);
        extension("first", VIEW_POINT, 1);

        assertEquals(List.of("/Extensions/first", "/Extensions/second"), paths(managerFor(VIEW_POINT).listAll()));
    }

    @Test
    void leavesTheDisabledExtensionsOutOfTheEnabledOnes() throws RepositoryException
    {
        extension("shown", VIEW_POINT, 1);
        extension("hidden", VIEW_POINT, 2).setProperty("ext:defaultDisabled", true);

        final ExtensionsManager manager = managerFor(VIEW_POINT);

        assertEquals(List.of("/Extensions/shown"), paths(manager.listEnabled()));
        assertEquals(2, manager.listAll().size());
    }

    @Test
    void servesTheExtensionsAsJson() throws RepositoryException
    {
        this.context.registerAdapter(Resource.class, JsonObject.class,
            (Function<Resource, JsonObject>) resource -> Json.createObjectBuilder()
                .add("name", resource.getName()).build());
        extension("shown", VIEW_POINT, 1);
        extension("hidden", VIEW_POINT, 2).setProperty("ext:defaultDisabled", true);

        final ExtensionsManager manager = managerFor(VIEW_POINT);

        assertEquals("[{\"name\":\"shown\"},{\"name\":\"hidden\"}]", manager.getAll());
        assertEquals("[{\"name\":\"shown\"}]", manager.getEnabled());
    }

    @Test
    void leavesOutAnExtensionThatCannotBeSerialized() throws RepositoryException
    {
        extension("shown", VIEW_POINT, 1);

        assertEquals("[]", managerFor(VIEW_POINT).getAll());
    }

    @Test
    void findsNothingWithoutAPoint()
    {
        final ExtensionsManager manager = new ExtensionsManager();
        manager.init(new SimpleBindings(Map.of("resolver", this.context.resourceResolver())));

        assertTrue(manager.listAll().isEmpty());
    }

    @Test
    void findsNothingWhenTheQueryFails() throws RepositoryException
    {
        final Session failing = mock(Session.class, RETURNS_DEEP_STUBS);
        when(failing.getWorkspace().getQueryManager()).thenThrow(new RepositoryException("no queries"));
        final ResourceResolver resolver = mock(ResourceResolver.class);
        when(resolver.adaptTo(Session.class)).thenReturn(failing);
        final ExtensionsManager manager = new ExtensionsManager();

        manager.init(new SimpleBindings(Map.of("uixp", VIEW_POINT, "resolver", resolver)));

        assertTrue(manager.listAll().isEmpty());
    }

    @Test
    void findsNothingWithoutARepositorySession()
    {
        final ExtensionsManager manager = new ExtensionsManager();

        manager.init(new SimpleBindings(Map.of("uixp", VIEW_POINT, "resolver", mock(ResourceResolver.class))));

        assertTrue(manager.listAll().isEmpty());
    }

    private Node extension(final String name, final String pointId, final long defaultOrder)
        throws RepositoryException
    {
        final Node extension = this.extensions.addNode(name, "ext:Extension");
        extension.setProperty("ext:pointId", pointId);
        extension.setProperty("ext:name", name);
        extension.setProperty("defaultOrder", defaultOrder);
        return extension;
    }

    private ExtensionsManager managerFor(final String pointId) throws RepositoryException
    {
        this.session.save();
        final ExtensionsManager manager = new ExtensionsManager();
        manager.init(new SimpleBindings(Map.of("uixp", pointId, "resolver", this.context.resourceResolver())));
        return manager;
    }

    private static List<String> paths(final List<Resource> resources)
    {
        return resources.stream().map(Resource::getPath).collect(Collectors.toList());
    }
}
