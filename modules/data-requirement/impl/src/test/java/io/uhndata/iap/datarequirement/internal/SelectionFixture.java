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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.datarequirement.models.Catalogue;
import io.uhndata.iap.datarequirement.models.CatalogueVersion;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Submission;

/**
 * A submission whose schema asks for data from one catalogue, beside a set of questions. The context must be
 * JCR-backed: the references are real.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SelectionFixture
{
    static final String DATA = "data";

    static final String SUBMISSION_PATH = "/Submissions/aStudy";

    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String VERSION_PATH = "/Schemas/study/v1";

    private static final String DATA_PATH = VERSION_PATH + "/" + DATA;

    private final SlingContext context;

    private final Referenceables references;

    private final Set<String> hidden = new HashSet<>();

    private SelectionFixture(final SlingContext context)
    {
        this.context = context;
        this.references = new Referenceables(context);
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
        context.create().resource(CATALOGUE_PATH, Map.of(TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        context.create().resource(CATALOGUE_PATH + "/v1", Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", "2026-02", "tags", new String[] {"active"}));
        context.create().resource(VERSION_PATH, Map.of(TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0"));
        context.create().resource(DATA_PATH, Map.of(TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT));
        fixture.references.setReference(DATA_PATH, "catalogue", CATALOGUE_PATH);
        fixture.references.identify(DATA_PATH);
        context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT));
        context.create().resource(SUBMISSION_PATH, Map.of(TYPE, Submission.RESOURCE_TYPE));
        fixture.references.setReference(SUBMISSION_PATH, "schemaVersion", VERSION_PATH);
        return fixture;
    }

    /**
     * Whether each part of the schema applies, which is every part unless {@link #hide} said otherwise.
     *
     * @return a condition evaluator answering that
     */
    ConditionEvaluator conditions()
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !this.hidden.contains(((Content) call.getArgument(0)).getName()));
        return conditions;
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
     * Records a selection answering the data requirement.
     *
     * @param fields the chosen field keys, none for an empty part
     * @return the selection's resource
     */
    Resource select(final String... fields)
    {
        final Resource selection = this.context.create().resource(SUBMISSION_PATH + "/selection", Map.of(
            "jcr:primaryType", Selection.NODE_TYPE, TYPE, Selection.RESOURCE_TYPE, "fields", fields));
        this.references.setReference(selection.getPath(), "fulfills", DATA_PATH);
        return selection;
    }

    /**
     * The submission's selections. A mock repository autocreates nothing from the CND, so the resource type of a
     * selection the code under test created is filled in here.
     *
     * @return the selections, in repository order
     */
    List<Resource> selections()
    {
        final Resource submission = submission();
        submission.getChildren().forEach(child -> {
            if (Selection.NODE_TYPE.equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                Objects.requireNonNull(child.adaptTo(ModifiableValueMap.class)).put(TYPE, Selection.RESOURCE_TYPE);
            }
        });
        return StreamSupport.stream(submission.getChildren().spliterator(), false)
            .filter(child -> Selection.RESOURCE_TYPE.equals(child.getResourceType()))
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
     * The submission as the code under test is handed it. jcr-mock answers nothing about versioning, so it reads
     * as a node that is already checked out.
     *
     * @return the submission's resource, writable
     * @throws RepositoryException never; stubbing names the method rather than calling it
     */
    Resource writableSubmission() throws RepositoryException
    {
        final Node checkedOut = Mockito.mock(Node.class);
        Mockito.when(checkedOut.isCheckedOut()).thenReturn(true);
        return new ResourceWrapper(submission())
        {
            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(checkedOut) : super.adaptTo(type);
            }
        };
    }
}
