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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;

import static io.uhndata.iap.datarequirement.internal.SelectionFixture.BIRTH_DATE;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.SUBMISSION_PATH;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.V1;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.V2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DataRequirementDescriber}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DataRequirementDescriberTest
{
    private static final String CATALOGUE_VERSION = "catalogueVersion";

    // JCR-backed: the requirement points at its catalogue version, and the selection at its requirement, with real
    // REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DataRequirementDescriber describer = new DataRequirementDescriber();

    private SelectionFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = SelectionFixture.installIn(this.context);
    }

    @Test
    void claimsADataRequirementAndNothingElse()
    {
        assertTrue(this.describer.handles(requirement(SelectionFixture.DATA_PATH)));
        assertFalse(this.describer.handles(requirement(SelectionFixture.DETAILS_PATH)));
    }

    @Test
    void offersTheVersionTheRequirementNames()
    {
        this.fixture.select();

        final JsonObject json = describe();

        assertEquals(V1, json.getString(CATALOGUE_VERSION));
        assertEquals("2026-02", json.getString("catalogueVersionLabel"));
        assertTrue(fieldsOf(json).isEmpty());
    }

    @Test
    void reportsWhatHasBeenChosen()
    {
        this.fixture.select(BIRTH_DATE);

        assertEquals(List.of(BIRTH_DATE), fieldsOf(describe()));
    }

    @Test
    void readsOnlyTheSelectionThatAnswersThisRequirement()
    {
        this.context.create().resource(SUBMISSION_PATH + "/elsewhere", Map.of(
            "sling:resourceType", Selection.RESOURCE_TYPE, "fields", new String[] {BIRTH_DATE}));
        this.fixture.setReference(SUBMISSION_PATH + "/elsewhere", "fulfills", SelectionFixture.DETAILS_PATH);

        assertTrue(fieldsOf(describe()).isEmpty());
    }

    @Test
    void keepsOfferingTheNamedVersionWhenAnotherIsActive()
    {
        this.fixture.select(BIRTH_DATE);
        this.fixture.retire(V1);
        this.fixture.addVersion(V2, "2026-08", "birthDate");

        final JsonObject json = describe();

        assertEquals(V1, json.getString(CATALOGUE_VERSION));
        assertEquals("2026-02", json.getString("catalogueVersionLabel"));
    }

    private Requirement requirement(final String path)
    {
        return Objects.requireNonNull(this.context.resourceResolver().getResource(path)).adaptTo(Requirement.class);
    }

    private JsonObject describe()
    {
        final Submission submission = this.fixture.submission().adaptTo(Submission.class);
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.describer.describe(requirement(SelectionFixture.DATA_PATH), submission, json);
        return json.build();
    }

    private List<String> fieldsOf(final JsonObject json)
    {
        return json.getJsonArray("fields").stream()
            .map(value -> ((JsonString) value).getString())
            .toList();
    }
}
