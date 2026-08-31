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

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.datarequirement.models.Collection;
import io.uhndata.iap.datarequirement.models.Database;
import io.uhndata.iap.datarequirement.models.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DataCatalogueFieldKeyProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DataCatalogueFieldKeyProcessorTest
{
    private static final String VERSION_PATH = "/Catalogues/clinical/v1";

    private static final String COLLECTION_PATH = VERSION_PATH + "/records/patient";

    private static final String KEY = "key";

    private static final String TYPE = "sling:resourceType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String IDENTIFIER = "identifier";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DataCatalogueFieldKeyProcessor processor = new DataCatalogueFieldKeyProcessor();

    private final Function<Node, JsonValue> serializeNode = node -> Json.createValue("the serialized node");

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Database.class, Collection.class, Field.class);
        this.context.create().resource(VERSION_PATH + "/records", Map.of(TYPE, Database.RESOURCE_TYPE, IDENTIFIER,
            "records"));
        this.context.create().resource(COLLECTION_PATH, Map.of(TYPE, Collection.RESOURCE_TYPE, IDENTIFIER,
            "Patient"));
        this.context.create().resource(COLLECTION_PATH + "/birthDate", Map.of(PRIMARY_TYPE, "datareq:Field",
            TYPE, Field.RESOURCE_TYPE, IDENTIFIER, "birthDate"));
    }

    @Test
    void isOnForEverySerialization()
    {
        assertEquals("dataCatalogueFieldKey", this.processor.getName());
        assertEquals(50, this.processor.getPriority());
        assertTrue(this.processor.isEnabledByDefault(Mockito.mock(Resource.class)));
    }

    @Test
    void addsToAFieldTheKeyASelectionRecordsItAs()
    {
        assertEquals("records/Patient/birthDate", serialize(COLLECTION_PATH + "/birthDate").getString(KEY));
    }

    @Test
    void addsNothingToTheLevelsAboveAField()
    {
        assertFalse(serialize(COLLECTION_PATH).containsKey(KEY));
    }

    @Test
    void addsNothingToAFieldOutsideACollection()
    {
        this.context.create().resource(VERSION_PATH + "/stray", Map.of(PRIMARY_TYPE, "datareq:Field",
            TYPE, Field.RESOURCE_TYPE, IDENTIFIER, "stray"));

        assertFalse(serialize(VERSION_PATH + "/stray").containsKey(KEY));
    }

    @Test
    void addsNothingToANodeItCannotRead() throws RepositoryException
    {
        final Node unreadable = Mockito.mock(Node.class);
        Mockito.when(unreadable.isNodeType(Mockito.anyString())).thenThrow(new RepositoryException("gone"));
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.start(resource(VERSION_PATH));
        this.processor.leave(unreadable, json, this.serializeNode);
        this.processor.end(resource(VERSION_PATH));

        assertTrue(json.build().isEmpty());
    }

    private JsonObject serialize(final String path)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.processor.start(resource(VERSION_PATH));
        this.processor.leave(Objects.requireNonNull(resource(path).adaptTo(Node.class)), json, this.serializeNode);
        this.processor.end(resource(VERSION_PATH));
        return json.build();
    }

    private Resource resource(final String path)
    {
        return Objects.requireNonNull(this.context.resourceResolver().getResource(path));
    }
}
