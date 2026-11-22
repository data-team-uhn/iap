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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.datarequirement.models.Catalogue;
import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.Collection;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Database;
import io.uhndata.iap.datarequirement.models.Field;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.testing.PropertyTagging;
import io.uhndata.iap.utils.testing.Referenceables;

/**
 * A draft submission whose schema asks for data from version {@link #V1} of a catalogue, beside a set of questions.
 * That version is active and offers {@link #BIRTH_DATE} and {@link #GENDER}. The context must be on a {@code JCR_MOCK}
 * repository: the references are real.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SelectionFixture
{
    static final String DATA = "data";

    static final String DETAILS = "details";

    static final String SUBMISSION_PATH = "/Submissions/aStudy";

    static final String VERSION_PATH = "/Schemas/study/v1";

    static final String DATA_PATH = VERSION_PATH + "/" + DATA;

    static final String DETAILS_PATH = VERSION_PATH + "/" + DETAILS;

    static final String CATALOGUE_PATH = "/Catalogues/clinical";

    static final String V1 = CATALOGUE_PATH + "/v1";

    static final String V2 = CATALOGUE_PATH + "/v2";

    static final String BIRTH_DATE = "records/Patient/birthDate";

    static final String GENDER = "records/Patient/gender";

    static final String REQUESTER = "demo-requester";

    static final String DATA_LABEL = "Which data do you need?";

    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String TAGS = "tags";

    private static final String IDENTIFIER = "identifier";

    private final SlingContext context;

    private final Referenceables references;

    private final Set<String> hidden = new HashSet<>();

    // A mock of the submission's node, because jcr-mock does not implement versioning
    private final Node node = Mockito.mock(Node.class);

    private SelectionFixture(final SlingContext context)
    {
        this.context = context;
        this.references = new Referenceables(context.resourceResolver());
    }

    /**
     * Creates the catalogue, the schema version asking for data and for answers, and the submission.
     *
     * @param context the test's JCR-backed Sling context
     * @return the fixture
     */
    static SelectionFixture installIn(final SlingContext context)
    {
        final SelectionFixture fixture = new SelectionFixture(context);
        context.addModelsForClasses(Schema.class, SchemaVersion.class, FormRequirement.class, DataRequirement.class,
            Catalogue.class, CatalogueVersion.class, Database.class, Collection.class, Field.class, Selection.class,
            Submission.class);
        PropertyTagging.installIn(context);
        context.registerService(ConditionEvaluator.class, fixture.conditions());
        context.create().resource(CATALOGUE_PATH, Map.of(TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        fixture.addVersion(V1, "2026-02", "birthDate", "gender");
        context.create().resource("/Schemas/study", Map.of(TYPE, Schema.RESOURCE_TYPE, "title", "Retrospective study"));
        context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", TAGS, new String[] {"active"}));
        context.create().resource(DATA_PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", DATA_LABEL));
        fixture.references.setReference(DATA_PATH, "catalogue", V1);
        // A requirement that is not a data requirement
        context.create().resource(DETAILS_PATH, Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Study details"));
        fixture.references.identify(DATA_PATH, DETAILS_PATH);
        context.create().resource(SUBMISSION_PATH, Map.of(TYPE, Submission.RESOURCE_TYPE, "title", "A chart review",
            "createdBy", REQUESTER, TAGS, new String[] {"draft"}));
        fixture.references.setReference(SUBMISSION_PATH, "schemaVersion", VERSION_PATH);
        fixture.stubCheckedOut(true);
        return fixture;
    }

    /**
     * Makes one part of the schema stop applying.
     *
     * @param name the part's node name
     */
    void hide(final String name)
    {
        this.hidden.add(name);
    }

    /**
     * Publishes an active version of the catalogue.
     *
     * @param path where the version goes, {@link #V1} or {@link #V2}
     * @param label the version's readable label
     * @param fields the source names of its fields, each in collection {@code records/Patient}
     */
    void addVersion(final String path, final String label, final String... fields)
    {
        this.context.create().resource(path, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", label, TAGS, new String[] {"active"}));
        this.context.create().resource(path + "/records", Map.of(TYPE, Database.RESOURCE_TYPE, IDENTIFIER, "records"));
        this.context.create().resource(path + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, IDENTIFIER, "Patient"));
        for (final String field : fields) {
            this.context.create().resource(path + "/records/patient/" + field, Map.of(
                TYPE, Field.RESOURCE_TYPE, IDENTIFIER, field));
        }
        this.references.identify(path);
    }

    /**
     * Retires a catalogue version, or the catalogue and with it every version.
     *
     * @param path the path of the version or of the catalogue
     */
    void retire(final String path)
    {
        modify(path, TAGS, new String[] {"retired"});
    }

    /**
     * Replaces the submission's tags, as sending it does.
     *
     * @param tags the tags it carries from now on
     */
    void tagSubmission(final String... tags)
    {
        modify(SUBMISSION_PATH, TAGS, tags);
    }

    /**
     * Gives the data requirement its selection, holding the given keys. With no keys it is the empty
     * part the parts step makes, which has no {@code fields} property at all.
     *
     * @param fields the chosen field keys, none for an empty part
     * @return the selection's resource
     */
    Resource select(final String... fields)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            PRIMARY_TYPE, Selection.NODE_TYPE, TYPE, Selection.RESOURCE_TYPE));
        if (fields.length > 0) {
            properties.put("fields", fields);
        }
        final Resource selection = this.context.create().resource(SUBMISSION_PATH + "/selection", properties);
        this.references.setReference(selection.getPath(), "fulfills", DATA_PATH);
        return selection;
    }

    /**
     * Points a REFERENCE property of one node at another.
     *
     * @param fromPath the path of the node holding the property
     * @param property the property's name
     * @param toPath the path of the node it points at
     */
    void setReference(final String fromPath, final String property, final String toPath)
    {
        this.references.setReference(fromPath, property, toPath);
    }

    /**
     * The submission's selections, found by primary type: the code under test sets no other type on them.
     *
     * @return the selections, in repository order
     */
    List<Resource> selections()
    {
        this.context.resourceResolver().refresh();
        return StreamSupport.stream(submission().getChildren().spliterator(), false)
            .filter(child -> Selection.NODE_TYPE.equals(child.getValueMap().get(PRIMARY_TYPE, String.class)))
            .toList();
    }

    /**
     * The submission, read afresh.
     *
     * @return the submission's resource
     */
    Resource submission()
    {
        return Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH));
    }

    /**
     * {@link #writableSubmission(ResourceResolver)} through the test's own resolver.
     *
     * @return the submission's resource
     */
    Resource writableSubmission()
    {
        return writableSubmission(this.context.resourceResolver());
    }

    /**
     * The submission as the code under test is handed it, reading as checked out unless {@link #checkIn} said
     * otherwise.
     *
     * @param resolver the resolver the resource reports, and the code under test writes through
     * @return the submission's resource
     */
    Resource writableSubmission(final ResourceResolver resolver)
    {
        final Node versioned = this.node;
        return new ResourceWrapper(submission())
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(versioned) : super.adaptTo(type);
            }
        };
    }

    /**
     * Makes the submission read as checked in, so whatever writes under it has to check it out first.
     *
     * @return the version manager the checkout has to go through
     */
    VersionManager checkIn()
    {
        try {
            final Session session = Mockito.mock(Session.class);
            final Workspace workspace = Mockito.mock(Workspace.class);
            final VersionManager versions = Mockito.mock(VersionManager.class);
            stubCheckedOut(false);
            Mockito.when(this.node.isNodeType("mix:versionable")).thenReturn(true);
            Mockito.when(this.node.getPath()).thenReturn(SUBMISSION_PATH);
            Mockito.when(this.node.getSession()).thenReturn(session);
            Mockito.when(session.getWorkspace()).thenReturn(workspace);
            Mockito.when(workspace.getVersionManager()).thenReturn(versions);
            return versions;
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private ConditionEvaluator conditions()
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !this.hidden.contains(((Content) call.getArgument(0)).getName()));
        return conditions;
    }

    private void stubCheckedOut(final boolean checkedOut)
    {
        try {
            Mockito.when(this.node.isCheckedOut()).thenReturn(checkedOut);
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modify(final String path, final String property, final Object value)
    {
        try {
            final Resource resource = Objects.requireNonNull(this.context.resourceResolver().getResource(path));
            Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class)).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }
}
