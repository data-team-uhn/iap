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
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.datarequirement.models.Catalogue;
import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.Collection;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Database;
import io.uhndata.iap.datarequirement.models.Field;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;

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
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String V1 = CATALOGUE_PATH + "/v1";

    private static final String V2 = CATALOGUE_PATH + "/v2";

    private static final String VERSION_PATH = "/Schemas/study/v1";

    private static final String DATA_PATH = VERSION_PATH + "/data";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/study";

    private static final String BIRTH_DATE = "records/Patient/birthDate";

    // JCR-backed: the selection points at its requirement and its version with real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DataRequirementDescriber describer = new DataRequirementDescriber();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Requirement.class, FormRequirement.class, DataRequirement.class, Catalogue.class,
            CatalogueVersion.class, Database.class, Collection.class, Field.class, Selection.class,
            Submission.class);

        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        version(V1, "2026-02", true);

        this.context.create().resource("/Schemas/study", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Retrospective study", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(DATA_PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Which data do you need?"));
        reference(DATA_PATH, CATALOGUE_PATH, "catalogue");

        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A chart review"));
        reference(SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
    }

    private void version(final String path, final String label, final boolean active)
    {
        this.context.create().resource(path, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", label, "active", active));
        this.context.create().resource(path + "/records", Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(path + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        this.context.create().resource(path + "/records/patient/birthDate", Map.of(
            TYPE, Field.RESOURCE_TYPE, "identifier", "birthDate"));
    }

    /** Records a selection of {@code fields} against the data requirement, chosen from {@code versionPath}. */
    private void choose(final String versionPath, final String... fields)
    {
        this.context.create().resource(SUBMISSION_PATH + "/chosen", Map.of(
            TYPE, Selection.RESOURCE_TYPE, "fields", fields));
        reference(SUBMISSION_PATH + "/chosen", DATA_PATH, "fulfills");
        reference(SUBMISSION_PATH + "/chosen", versionPath, "catalogueVersion");
    }

    private JsonObject describe()
    {
        final Resource resource = this.context.resourceResolver().getResource(DATA_PATH);
        final Submission submission = Objects.requireNonNull(
            this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Submission.class);
        final JsonObjectBuilder json = Json.createObjectBuilder();
        this.describer.describe(Objects.requireNonNull(resource).adaptTo(Requirement.class), submission, json);
        return json.build();
    }

    private List<String> fieldsOf(final JsonObject json)
    {
        return json.getJsonArray("fields").stream()
            .map(value -> ((JsonString) value).getString())
            .collect(Collectors.toList());
    }

    @Test
    void claimsADataRequirementAndNothingElse()
    {
        final Requirement data = Objects.requireNonNull(
            this.context.resourceResolver().getResource(DATA_PATH)).adaptTo(Requirement.class);
        final Resource other = this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Study details"));

        assertTrue(this.describer.handles(data));
        assertFalse(this.describer.handles(other.adaptTo(Requirement.class)));
    }

    // Before anything is chosen the form offers whatever the catalogue is publishing now
    @Test
    void offersTheCurrentVersionWhileNothingHasBeenChosen()
    {
        final JsonObject json = describe();

        assertEquals(V1, json.getString("catalogueVersion"));
        assertEquals("2026-02", json.getString("catalogueVersionLabel"));
        assertTrue(fieldsOf(json).isEmpty());
    }

    @Test
    void reportsWhatHasBeenChosen()
    {
        choose(V1, BIRTH_DATE);

        assertEquals(List.of(BIRTH_DATE), fieldsOf(describe()));
    }

    // A submission may hold several selections, and one is this requirement's only if it says so: found by the
    // reference it holds rather than by anything about where it sits or what it is called
    @Test
    void readsOnlyTheSelectionThatAnswersThisRequirement()
    {
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Study details"));
        // One answering nothing at all, one answering a different requirement
        this.context.create().resource(SUBMISSION_PATH + "/unanswered", Map.of(
            TYPE, Selection.RESOURCE_TYPE, "fields", new String[] {BIRTH_DATE}));
        this.context.create().resource(SUBMISSION_PATH + "/elsewhere", Map.of(
            TYPE, Selection.RESOURCE_TYPE, "fields", new String[] {BIRTH_DATE}));
        reference(SUBMISSION_PATH + "/elsewhere", VERSION_PATH + "/details", "fulfills");

        assertTrue(fieldsOf(describe()).isEmpty());
    }

    // The whole point: a submitter who started before a republication carries on where they started
    @Test
    void keepsOfferingTheVersionTheSelectionWasMadeAgainst()
    {
        choose(V1, BIRTH_DATE);
        version(V2, "2026-08", true);

        final JsonObject json = describe();

        assertEquals(V1, json.getString("catalogueVersion"));
        assertEquals("2026-02", json.getString("catalogueVersionLabel"));
    }

    // A reader has to tell "nothing chosen" from "the catalogue could not be read at all"
    @Test
    void saysThereIsNoVersionRatherThanInventingOne()
    {
        final Resource loose = this.context.create().resource(VERSION_PATH + "/unlinked", Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Which data?"));
        final Submission submission = Objects.requireNonNull(
            this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Submission.class);
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.describer.describe(loose.adaptTo(Requirement.class), submission, json);
        final JsonObject described = json.build();

        assertFalse(described.containsKey("catalogueVersion"));
        assertTrue(described.getJsonArray("fields").isEmpty());
    }

    private void reference(final String fromPath, final String toPath, final String property)
    {
        try {
            Objects.requireNonNull(this.context.resourceResolver().getResource(fromPath)).adaptTo(Node.class)
                .setProperty(property, Objects.requireNonNull(
                    this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }
}
