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
package io.uhndata.iap.datarequirement.internal;

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CatalogueSummaryProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CatalogueSummaryProcessorTest
{
    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String VERSION_PATH = CATALOGUE_PATH + "/v1";

    private static final String REQUIREMENT_TYPE = "datareq:DataRequirement";

    private static final String CATALOGUE = "catalogue";

    /** Stands in for the version as `dereference` inlined it, every database included. */
    private static final JsonValue INLINED = Json.createObjectBuilder()
        .add("jcr:primaryType", "datareq:CatalogueVersion")
        .add("records", Json.createObjectBuilder())
        .build();

    private final CatalogueSummaryProcessor processor = new CatalogueSummaryProcessor();

    private final Function<Node, JsonValue> serializeNode = node -> Json.createValue("the serialized node");

    @Test
    void isOnForEverySerializationAfterDereference()
    {
        assertEquals("catalogueSummary", this.processor.getName());
        assertTrue(this.processor.getPriority() > 10);
        assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    void summarizesTheCatalogueVersionARequirementNames() throws RepositoryException
    {
        final Node version = named(VERSION_PATH, "version", "2026-08");
        final Node catalogue = named(CATALOGUE_PATH, "title", "Clinical");
        Mockito.when(version.getParent()).thenReturn(catalogue);

        final JsonValue summary = process(REQUIREMENT_TYPE, CATALOGUE, version);

        assertEquals(Json.createObjectBuilder()
            .add("@path", VERSION_PATH)
            .add("version", "2026-08")
            .add(CATALOGUE, Json.createObjectBuilder().add("@path", CATALOGUE_PATH).add("title", "Clinical"))
            .build(), summary);
    }

    @Test
    void leavesOutNamesTheReaderCannotSee() throws RepositoryException
    {
        final Node version = unnamed(VERSION_PATH);
        final Node catalogue = unnamed(CATALOGUE_PATH);
        Mockito.when(version.getParent()).thenReturn(catalogue);

        final JsonValue summary = process(REQUIREMENT_TYPE, CATALOGUE, version);

        assertEquals(Json.createObjectBuilder()
            .add("@path", VERSION_PATH)
            .add(CATALOGUE, Json.createObjectBuilder().add("@path", CATALOGUE_PATH))
            .build(), summary);
    }

    @Test
    void leavesOtherPropertiesAlone() throws RepositoryException
    {
        assertSame(INLINED, process(REQUIREMENT_TYPE, "fulfills", unnamed(VERSION_PATH)));
    }

    @Test
    void leavesTheSamePropertyOnAnotherNodeAlone() throws RepositoryException
    {
        assertSame(INLINED, process("sch:FormRequirement", CATALOGUE, unnamed(VERSION_PATH)));
    }

    @Test
    void leavesAVersionTheReaderCannotReachAsItWas() throws RepositoryException
    {
        final Node node = node(REQUIREMENT_TYPE);
        final Property property = property(CATALOGUE);
        Mockito.when(property.getNode()).thenThrow(new RepositoryException("unreachable"));

        assertSame(INLINED, this.processor.processProperty(node, property, INLINED, this.serializeNode));
    }

    private JsonValue process(final String nodeType, final String name, final Node target)
        throws RepositoryException
    {
        final Property property = property(name);
        Mockito.when(property.getNode()).thenReturn(target);
        return this.processor.processProperty(node(nodeType), property, INLINED, this.serializeNode);
    }

    private static Node node(final String nodeType) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(Mockito.anyString())).thenAnswer(call -> nodeType.equals(call.getArgument(0)));
        return node;
    }

    private static Property property(final String name) throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(property.getName()).thenReturn(name);
        return property;
    }

    private static Node unnamed(final String path) throws RepositoryException
    {
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(path);
        return target;
    }

    private static Node named(final String path, final String nameProperty, final String name)
        throws RepositoryException
    {
        final Node target = unnamed(path);
        final Property named = Mockito.mock(Property.class);
        Mockito.when(target.hasProperty(nameProperty)).thenReturn(true);
        Mockito.when(target.getProperty(nameProperty)).thenReturn(named);
        Mockito.when(named.getString()).thenReturn(name);
        return target;
    }
}
