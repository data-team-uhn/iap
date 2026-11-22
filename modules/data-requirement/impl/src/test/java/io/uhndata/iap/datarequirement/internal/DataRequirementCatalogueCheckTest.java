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

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.datarequirement.models.CatalogueVersion;

import static io.uhndata.iap.datarequirement.internal.SelectionFixture.CATALOGUE_PATH;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.DATA_PATH;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.V1;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.VERSION_PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DataRequirementCatalogueCheck}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DataRequirementCatalogueCheckTest
{
    private static final String NOT_OPEN = "Data requirement \"Which data do you need?\" offers version 2026-02 of "
        + "\"Clinical data\", which is not open to new schema versions";

    // JCR-backed: the requirement points at its catalogue version with a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DataRequirementCatalogueCheck check = new DataRequirementCatalogueCheck();

    private SelectionFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = SelectionFixture.installIn(this.context);
    }

    @Test
    void appliesToSchemaVersionsOnly()
    {
        assertTrue(this.check.appliesTo(resource(VERSION_PATH)));
        assertFalse(this.check.appliesTo(resource(V1)));
    }

    @Test
    void letsASchemaVersionNamingAnActiveCatalogueVersionGoOn()
    {
        assertEquals(List.of(), this.check.check(resource(VERSION_PATH)));
    }

    @Test
    void refusesARetiredCatalogueVersion()
    {
        this.fixture.retire(V1);

        assertEquals(List.of(NOT_OPEN), this.check.check(resource(VERSION_PATH)));
    }

    @Test
    void refusesAnActiveVersionOfARetiredCatalogue()
    {
        this.fixture.retire(CATALOGUE_PATH);

        assertEquals(List.of(NOT_OPEN), this.check.check(resource(VERSION_PATH)));
    }

    @Test
    void refusesAReferenceToSomethingThatIsNotACatalogueVersion()
    {
        this.fixture.identify(CATALOGUE_PATH);
        this.fixture.setReference(DATA_PATH, "catalogue", CATALOGUE_PATH);

        assertEquals(List.of("Data requirement \"Which data do you need?\" names no catalogue version"),
            this.check.check(resource(VERSION_PATH)));
    }

    @Test
    void namesAVersionOutsideACatalogueByItsPath()
    {
        final String loose = "/Catalogues/loose";
        this.context.create().resource(loose, Map.of("sling:resourceType", CatalogueVersion.RESOURCE_TYPE,
            "version", "1"));
        this.fixture.identify(loose);
        this.fixture.setReference(DATA_PATH, "catalogue", loose);

        assertEquals(List.of("Data requirement \"Which data do you need?\" offers " + loose
            + ", which is not open to new schema versions"), this.check.check(resource(VERSION_PATH)));
    }

    private Resource resource(final String path)
    {
        return Objects.requireNonNull(this.context.resourceResolver().getResource(path));
    }
}
