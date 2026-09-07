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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

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
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SaveDataSelectionHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SaveDataSelectionHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String REQUESTER = "demo-requester";

    private static final String CATALOGUE_PATH = "/Catalogues/clinical";

    private static final String V1 = CATALOGUE_PATH + "/v1";

    private static final String V2 = CATALOGUE_PATH + "/v2";

    private static final String VERSION_PATH = "/Schemas/study/v1";

    private static final String DATA_PATH = VERSION_PATH + "/data";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/study";

    private static final String BIRTH_DATE = "records/Patient/birthDate";

    private static final String GENDER = "records/Patient/gender";

    // JCR-backed rather than the plain mock: the handler writes real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SaveDataSelectionHandler handler = new SaveDataSelectionHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Requirement.class, FormRequirement.class, DataRequirement.class, Catalogue.class,
            CatalogueVersion.class, Database.class, Collection.class, Field.class, Selection.class,
            Submission.class, Activity.class);
        Tagging.enable(this.context);

        this.context.create().resource(CATALOGUE_PATH, Map.of(
            TYPE, Catalogue.RESOURCE_TYPE, "title", "Clinical data"));
        version(V1, "2026-02", true, "birthDate", "gender");

        this.context.create().resource("/Schemas/study", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Retrospective study", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(DATA_PATH, Map.of(
            TYPE, DataRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Which data do you need?"));
        reference(this.context.resourceResolver().getResource(DATA_PATH), CATALOGUE_PATH, "catalogue");
        // A requirement of a kind nobody chooses data for, so the search is not simply "the child by that name"
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Study details"));

        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A chart review", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    /** A catalogue version holding {@code records/Patient/<field>} for each name given. */
    private void version(final String path, final String label, final boolean active, final String... fields)
    {
        this.context.create().resource(path, Map.of(
            TYPE, CatalogueVersion.RESOURCE_TYPE, "version", label, "active", active));
        this.context.create().resource(path + "/records", Map.of(
            TYPE, Database.RESOURCE_TYPE, "identifier", "records"));
        this.context.create().resource(path + "/records/patient", Map.of(
            TYPE, Collection.RESOURCE_TYPE, "identifier", "Patient"));
        for (final String field : fields) {
            this.context.create().resource(path + "/records/patient/" + field, Map.of(
                TYPE, Field.RESOURCE_TYPE, "identifier", field));
        }
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SaveDataSelectionHandler.NAME, this.handler.getName());
    }

    @Test
    void recordsWhatWasChosenAgainstTheRequirementItAnswers() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));

        final Resource selection = onlySelection();
        assertArrayEquals(new String[] {BIRTH_DATE},
            selection.getValueMap().get("fields", String[].class));
        assertEquals(identifierOf(DATA_PATH), selection.getValueMap().get("fulfills", String.class));
    }

    // The whole point of the design: what a submitter chose from is recorded, so republishing cannot move it
    @Test
    void bindsTheSelectionToTheVersionItWasChosenFrom() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));

        assertEquals(identifierOf(V1), onlySelection().getValueMap().get("catalogueVersion", String.class));
    }

    @Test
    void namesTheSelectionSomethingOfItsOwn() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));

        // A UUID, not the requirement's name: what a selection answers is a reference, not a label
        assertEquals(36, onlySelection().getName().length());
    }

    @Test
    void takesARequirementNamedByItsFullPath() throws Exception
    {
        this.handler.execute(context(payload(DATA_PATH, new String[] {BIRTH_DATE})));

        assertEquals(identifierOf(DATA_PATH), onlySelection().getValueMap().get("fulfills", String.class));
    }

    // Saving twice leaves one selection, found by what it answers rather than by any name
    @Test
    void replacesWhatWasChosenBeforeInsteadOfRecordingItTwice() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));
        onlySelection();
        this.handler.execute(context(payload("data", new String[] {GENDER})));

        assertArrayEquals(new String[] {GENDER}, onlySelection().getValueMap().get("fields", String[].class));
    }

    @Test
    void clearsTheSelectionWhenNothingIsSent() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));
        onlySelection();
        this.handler.execute(context(Map.of(SaveDataSelectionHandler.REQUIREMENT, "data")));

        assertEquals(0, onlySelection().getValueMap().get("fields", String[].class).length);
    }

    @Test
    void acceptsASingleFieldSentOnItsOwn() throws Exception
    {
        this.handler.execute(context(payload("data", BIRTH_DATE)));

        assertArrayEquals(new String[] {BIRTH_DATE}, onlySelection().getValueMap().get("fields", String[].class));
    }

    // A second save stays on the bound version even after the catalogue has published a newer one
    @Test
    void keepsTheBoundVersionWhenTheCatalogueHasMovedOn() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));
        onlySelection();
        modify(this.context.resourceResolver().getResource(V1), "active", false);
        version(V2, "2026-08", true, "birthDate");

        this.handler.execute(context(payload("data", new String[] {GENDER})));

        assertEquals(identifierOf(V1), onlySelection().getValueMap().get("catalogueVersion", String.class));
    }

    // And that is what makes the binding mean something: gender is gone from v2 but still valid here
    @Test
    void judgesLaterSavesAgainstTheBoundVersionRatherThanTheCurrentOne() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));
        onlySelection();
        modify(this.context.resourceResolver().getResource(V1), "active", false);
        version(V2, "2026-08", true, "birthDate");

        this.handler.execute(context(payload("data", new String[] {GENDER})));

        assertArrayEquals(new String[] {GENDER}, onlySelection().getValueMap().get("fields", String[].class));
    }

    @Test
    void refusesAKeyTheCatalogueDoesNotOffer()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("data", new String[] {"records/Patient/invented"}))));

        assertTrue(refusal.getMessage().contains("records/Patient/invented"));
        // Refused before anything was written, so a rejected save leaves no half-made selection behind
        assertTrue(selections().isEmpty());
    }

    @Test
    void refusesASelectionThatNamesNoRequirement()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(SaveDataSelectionHandler.FIELDS,
                new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesASelectionNamingSomethingBlank()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("  ", new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesARequirementThisRequestDoesNotAsk()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("nothingLikeIt", new String[] {BIRTH_DATE}))));
    }

    // A form requirement is a requirement, but not one anybody chooses data for
    @Test
    void refusesARequirementOfAnotherKind()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("details", new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesWhenTheCatalogueHasPublishedNoVersion()
    {
        modify(this.context.resourceResolver().getResource(V1), "active", false);

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("data", new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesSomebodyElseChoosingForTheRequester()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload("data", new String[] {BIRTH_DATE}), "someone-else")));
    }

    @Test
    void refusesAChoiceOnceTheRequestHasBeenSent()
    {
        modify(this.target, "tags", new String[] {"submitted"});

        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload("data", new String[] {BIRTH_DATE}))));
    }

    @Test
    void reportsARepositoryThatCannotReadTheSelectionBack() throws Exception
    {
        this.handler.execute(context(payload("data", new String[] {BIRTH_DATE})));

        assertThrows(PersistenceException.class, () -> this.handler.execute(
            context(payload("data", new String[] {GENDER}), REQUESTER, blindTo(onlySelection().getPath()))));
    }

    @Test
    void reportsARepositoryThatCannotReadTheRequirementBack()
    {
        assertThrows(PersistenceException.class, () -> this.handler.execute(
            context(payload("data", new String[] {BIRTH_DATE}), REQUESTER, blindTo(DATA_PATH))));
    }

    @Test
    void translatesAFailedReferenceIntoAPersistenceFailure()
    {
        // The selection is created but cannot be adapted to a node, so what it answers cannot be recorded. That
        // has to reach the engine as a persistence problem it knows how to translate rather than as a raw
        // repository error escaping a handler. Sabotaged at creation because that is the one resource the handler
        // obtains through the resolver it was handed
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                return new ResourceWrapper(super.create(parent, name, properties))
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class, () -> this.handler.execute(
            context(payload("data", new String[] {BIRTH_DATE}), REQUESTER, sabotaged)));
        assertTrue(failure.getMessage().contains("Could not reference"));
    }

    /**
     * The engine's own resolver, except that one path has gone missing between being found and being read back.
     *
     * <p>A wrapper rather than a mock, because everything else has to keep working: the models resolve their
     * references through the session this delegates to, and a bare mock would break the read long before the
     * write this is about.</p>
     *
     * @param hidden the one path that will not resolve
     * @return a resolver that answers normally for everything else
     */
    private ResourceResolver blindTo(final String hidden)
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return hidden.equals(path) ? null : super.getResource(path);
            }
        };
    }

    private Map<String, Object> payload(final String requirement, final Object fields)
    {
        return Map.of(SaveDataSelectionHandler.REQUIREMENT, requirement, SaveDataSelectionHandler.FIELDS, fields);
    }

    /** The one selection the submission holds, failing the test if there is not exactly one. */
    private Resource onlySelection()
    {
        final List<Resource> selections = selections();
        assertEquals(1, selections.size());
        return selections.get(0);
    }

    /**
     * The selections the submission holds.
     *
     * <p>A mock repository registers no node types, so the {@code sling:resourceType} a real one autocreates from
     * the CND is absent and nothing would adapt to a selection — including the handler's own lookup for an
     * existing one. It is filled in here, which is also why this has to be called between two saves.</p>
     *
     * @return the selection resources, in repository order
     */
    private List<Resource> selections()
    {
        this.context.resourceResolver().refresh();
        final Resource submission = Objects.requireNonNull(
            this.context.resourceResolver().getResource(SUBMISSION_PATH));
        submission.getChildren().forEach(child -> {
            if ("datareq:Selection".equals(child.getValueMap().get("jcr:primaryType", String.class))
                && child.getValueMap().get(TYPE) == null) {
                modify(child, TYPE, Selection.RESOURCE_TYPE);
            }
        });
        return StreamSupport.stream(submission.getChildren().spliterator(), false)
            .filter(child -> Selection.RESOURCE_TYPE.equals(child.getResourceType()))
            .toList();
    }

    private String identifierOf(final String path)
    {
        try {
            return Objects.requireNonNull(this.context.resourceResolver().getResource(path))
                .adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            from.adaptTo(Node.class).setProperty(property, Objects.requireNonNull(
                this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modify(final Resource resource, final String property, final Object value)
    {
        try {
            Objects.requireNonNull(resource.adaptTo(ModifiableValueMap.class)).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        return context(payload, REQUESTER);
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent(SaveDataSelectionHandler.NAME, payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }
        };
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return submission;
            }

            @Override
            public String getActor()
            {
                return actor;
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return event;
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public Object getVariable(final String name)
            {
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
            }
        };
    }
}
