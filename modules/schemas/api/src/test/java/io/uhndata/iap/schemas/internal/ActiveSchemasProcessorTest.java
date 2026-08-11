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
package io.uhndata.iap.schemas.internal;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.SchemasHomepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ActiveSchemasProcessor}: which trees it applies to, and which of their children survive it.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ActiveSchemasProcessorTest
{
    private static final String ACTIVE = "active";

    private final ActiveSchemasProcessor processor = new ActiveSchemasProcessor();

    @Test
    void isNamedAfterWhatItLeavesIn()
    {
        assertEquals(ACTIVE, this.processor.getName());
    }

    @Test
    void runsAfterTheProcessorThatSerializesChildren()
    {
        // Discarding a child has to be the later word than turning it into JSON, or `deep` would put it back
        assertTrue(this.processor.getPriority() > 10);
    }

    @Test
    void isOnWithoutBeingAskedFor()
    {
        assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    void appliesToTheSchemaTreeAndNothingElse()
    {
        assertTrue(this.processor.canProcess(resourceOf(SchemasHomepage.RESOURCE_TYPE)));
        assertTrue(this.processor.canProcess(resourceOf(Schema.RESOURCE_TYPE)));
        assertTrue(this.processor.canProcess(resourceOf(SchemaVersion.RESOURCE_TYPE)));
        // A submission also has an `active`-looking life of its own, and this rule is not about it
        assertFalse(this.processor.canProcess(resourceOf("sub/Submission")));
    }

    @Test
    void keepsAnActiveSchema() throws RepositoryException
    {
        assertEquals(JsonValue.EMPTY_JSON_OBJECT, process(node("sch:Schema", true)));
    }

    @Test
    void dropsARetiredSchema() throws RepositoryException
    {
        assertNull(process(node("sch:Schema", false)));
    }

    @Test
    void dropsARetiredSchemaVersion() throws RepositoryException
    {
        assertNull(process(node("sch:SchemaVersion", false)));
    }

    @Test
    void dropsASchemaThatWasNeverOpened() throws RepositoryException
    {
        // The node type defaults `active` to false, so a schema is something someone deliberately opens; a missing
        // property is therefore an answer rather than a gap
        assertNull(process(node("sch:Schema", null)));
    }

    @Test
    void keepsWhatIsNeitherASchemaNorAVersion() throws RepositoryException
    {
        // A schema holds questions, sections and requirements, none of which this rule has an opinion about
        assertEquals(JsonValue.EMPTY_JSON_OBJECT, process(node("sch:Question", null)));
    }

    @Test
    void leavesAChildNothingHasSerializedAlone() throws RepositoryException
    {
        // Nothing to discard: without `deep` there is no child JSON, and inventing one here would serialize the
        // whole tree for a request that asked for one node
        assertNull(this.processor.processChild(Mockito.mock(Node.class), node("sch:Schema", true), null,
            candidate -> JsonValue.NULL));
    }

    @Test
    void keepsAChildItCannotRead() throws RepositoryException
    {
        // Hiding a schema that is in fact open would leave a submitter with nothing to choose and no way to tell
        // why; keeping a retired one costs at most a refusal from the server, which enforces this properly
        final Node unreadable = Mockito.mock(Node.class);
        Mockito.when(unreadable.isNodeType(Mockito.anyString())).thenThrow(new RepositoryException("boom"));

        assertEquals(JsonValue.EMPTY_JSON_OBJECT, process(unreadable));
    }

    private JsonValue process(final Node child)
    {
        return this.processor.processChild(Mockito.mock(Node.class), child, JsonValue.EMPTY_JSON_OBJECT,
            candidate -> JsonValue.NULL);
    }

    private static Resource resourceOf(final String resourceType)
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.isResourceType(Mockito.anyString())).thenReturn(false);
        Mockito.when(resource.isResourceType(resourceType)).thenReturn(true);
        return resource;
    }

    /**
     * A child node of the given type, either carrying {@code active} or not carrying it at all.
     *
     * @param nodeType the type it reports
     * @param active what its {@code active} property says, or {@code null} for a node without one
     * @return the mocked node
     * @throws RepositoryException never, since nothing here touches a repository
     */
    private static Node node(final String nodeType, final Boolean active) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(Mockito.anyString())).thenReturn(false);
        Mockito.when(node.isNodeType(nodeType)).thenReturn(true);
        if (active != null) {
            // Built into a local first: Mockito rejects a mock created inside an unfinished `when`
            final Property property = Mockito.mock(Property.class);
            Mockito.when(property.getBoolean()).thenReturn(active);
            Mockito.when(node.hasProperty(ACTIVE)).thenReturn(true);
            Mockito.when(node.getProperty(ACTIVE)).thenReturn(property);
        }
        return node;
    }
}
