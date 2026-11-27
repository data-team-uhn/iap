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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.version.VersionManager;

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

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.testing.RecordingTaskContext;
import io.uhndata.iap.workflows.testing.RecordingTaskContext.RecordedRole;

import static io.uhndata.iap.datarequirement.internal.SelectionFixture.BIRTH_DATE;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.DATA;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.DATA_PATH;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.GENDER;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.REQUESTER;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.SUBMISSION_PATH;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.V1;
import static io.uhndata.iap.datarequirement.internal.SelectionFixture.V2;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final String FIELDS = "fields";

    private static final String CATALOGUE_VERSION = "catalogueVersion";

    // JCR-backed rather than the plain mock: the handler writes real REFERENCEs
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SaveDataSelectionHandler handler = new SaveDataSelectionHandler();

    private SelectionFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = SelectionFixture.installIn(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("saveDataSelection", this.handler.getName());
    }

    @Test
    void recordsWhatWasChosenIntoTheRequirementsSelection() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE})));

        final Resource selection = onlySelection();
        assertEquals("selection", selection.getName());
        assertArrayEquals(new String[] {BIRTH_DATE}, selection.getValueMap().get(FIELDS, String[].class));
    }

    @Test
    void recordsOnTheFirstSaveTheVersionTheChoiceWasCheckedAgainst() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE})));

        assertEquals(this.fixture.identifierOf(V1), onlySelection().getValueMap().get(CATALOGUE_VERSION, String.class));
    }

    @Test
    void takesARequirementNamedByItsFullPath() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA_PATH, new String[] {BIRTH_DATE})));

        assertArrayEquals(new String[] {BIRTH_DATE}, onlySelection().getValueMap().get(FIELDS, String[].class));
    }

    @Test
    void replacesWhatWasChosenBefore() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE})));
        this.handler.execute(context(payload(DATA, new String[] {GENDER})));

        assertArrayEquals(new String[] {GENDER}, onlySelection().getValueMap().get(FIELDS, String[].class));
    }

    @Test
    void clearsTheSelectionWhenNothingIsSent() throws Exception
    {
        this.fixture.selectFrom(V1, BIRTH_DATE);

        this.handler.execute(context(Map.of(SaveDataSelectionHandler.REQUIREMENT_PARAMETER, DATA)));

        assertEquals(0, onlySelection().getValueMap().get(FIELDS, String[].class).length);
    }

    @Test
    void clearsTheSelectionWhenOneEmptyValueIsSent() throws Exception
    {
        this.fixture.selectFrom(V1, BIRTH_DATE);

        this.handler.execute(context(payload(DATA, "")));

        assertEquals(0, onlySelection().getValueMap().get(FIELDS, String[].class).length);
    }

    @Test
    void recordsEachKeyOnceAndNoBlankOnes() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE, "", BIRTH_DATE, GENDER, " "})));

        assertArrayEquals(new String[] {BIRTH_DATE, GENDER}, onlySelection().getValueMap().get(FIELDS, String[].class));
    }

    @Test
    void acceptsASingleFieldSentOnItsOwn() throws Exception
    {
        this.fixture.select();

        this.handler.execute(context(payload(DATA, BIRTH_DATE)));

        assertArrayEquals(new String[] {BIRTH_DATE}, onlySelection().getValueMap().get(FIELDS, String[].class));
    }

    // Gender is in v1, the recorded version, and not in v2
    @Test
    void keepsCheckingAgainstTheRecordedVersionWhenTheCatalogueHasMovedOn() throws Exception
    {
        this.fixture.selectFrom(V1, BIRTH_DATE);
        this.fixture.retire(V1);
        this.fixture.addVersion(V2, "2026-08", "birthDate");

        this.handler.execute(context(payload(DATA, new String[] {GENDER})));

        final Resource selection = onlySelection();
        assertArrayEquals(new String[] {GENDER}, selection.getValueMap().get(FIELDS, String[].class));
        assertEquals(this.fixture.identifierOf(V1), selection.getValueMap().get(CATALOGUE_VERSION, String.class));
    }

    @Test
    void recordsWhatItDidForTheHistory() throws Exception
    {
        this.fixture.select();
        final RecordingTaskContext task = context(payload(DATA, new String[] {BIRTH_DATE}));

        this.handler.execute(task);

        assertEquals(List.of(new RecordedRole(SUBMISSION_PATH + "/selection",
            "chose data for " + SelectionFixture.DATA_LABEL)), task.getRoles());
    }

    @Test
    void checksOutTheRequestBeforeRecordingIntoIt() throws Exception
    {
        this.fixture.select();
        final VersionManager versions = this.fixture.checkIn();
        Mockito.doAnswer(invocation -> {
            assertNull(this.fixture.selections().get(0).getValueMap().get(FIELDS, String[].class),
                "A choice was recorded before the request was checked out");
            return null;
        }).when(versions).checkout(SUBMISSION_PATH);

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE})));

        Mockito.verify(versions).checkout(SUBMISSION_PATH);
    }

    @Test
    void refusesAKeyTheCatalogueDoesNotOffer()
    {
        this.fixture.select();

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {"records/Patient/invented"}))));

        assertTrue(refusal.getMessage().contains("records/Patient/invented"));
        assertFalse(onlySelection().getValueMap().containsKey(FIELDS), "Refused before anything was written");
    }

    @Test
    void refusesARequirementWhoseConditionDoesNotHold()
    {
        this.fixture.select();
        this.fixture.hide(DATA);

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE}))));
        assertFalse(onlySelection().getValueMap().containsKey(FIELDS));
    }

    @Test
    void refusesASelectionThatNamesNoRequirement()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(
            context(Map.of(SaveDataSelectionHandler.FIELDS_PARAMETER, new String[] {BIRTH_DATE}))));
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

    @Test
    void refusesARequirementOfAnotherKind()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(SelectionFixture.DETAILS, new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesWhenTheCatalogueHasNoActiveVersion()
    {
        this.fixture.select();
        this.fixture.retire(V1);

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE}))));

        assertTrue(refusal.getMessage().contains("no active version"));
    }

    @Test
    void refusesSomebodyElseChoosingForTheRequester()
    {
        this.fixture.select();

        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE}), "someone-else")));
    }

    @Test
    void recordsAChoiceWhileTheRequestIsBackForChanges() throws Exception
    {
        this.fixture.select();
        this.fixture.tagSubmission("changes-requested");

        this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE})));

        assertArrayEquals(new String[] {BIRTH_DATE}, onlySelection().getValueMap().get(FIELDS, String[].class));
    }

    @Test
    void refusesAChoiceOnceTheRequestHasBeenSent()
    {
        this.fixture.select();
        this.fixture.tagSubmission("submitted");

        assertThrows(InvalidStateException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE}))));
    }

    @Test
    void refusesToRunOnAnythingButASubmission()
    {
        final RecordingTaskContext task = RecordingTaskContext.aimedAt(
            this.context.resourceResolver().getResource(SelectionFixture.CATALOGUE_PATH)).actingFor(REQUESTER)
            .forEvent(SaveDataSelectionHandler.HANDLER_NAME, payload(DATA, new String[] {BIRTH_DATE})).build();

        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(task));
    }

    @Test
    void failsWhenThePartsStepLeftTheRequirementNoSelection()
    {
        assertThrows(NullPointerException.class,
            () -> this.handler.execute(context(payload(DATA, new String[] {BIRTH_DATE}))));
    }

    @Test
    void translatesAFailedReferenceIntoAPersistenceFailure()
    {
        // The selection's node fails every call, so the version cannot be recorded. The engine must see a
        // PersistenceException, not a raw RepositoryException
        this.fixture.select();
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource resource = super.getResource(path);
                return resource == null || !path.endsWith("/selection") ? resource : new ResourceWrapper(resource)
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
            context(payload(DATA, new String[] {BIRTH_DATE}), REQUESTER, sabotaged)));

        assertTrue(failure.getMessage().contains("Cannot point"));
    }

    private Map<String, Object> payload(final String requirement, final Object fields)
    {
        return Map.of(SaveDataSelectionHandler.REQUIREMENT_PARAMETER, requirement,
            SaveDataSelectionHandler.FIELDS_PARAMETER, fields);
    }

    /** The one selection the submission holds, failing the test if there is not exactly one. */
    private Resource onlySelection()
    {
        final List<Resource> selections = this.fixture.selections();
        assertEquals(1, selections.size());
        return Objects.requireNonNull(selections.get(0));
    }

    private RecordingTaskContext context(final Map<String, Object> payload)
    {
        return context(payload, REQUESTER);
    }

    private RecordingTaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private RecordingTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        return RecordingTaskContext.aimedAt(this.fixture.writableSubmission(resolver)).actingFor(actor)
            .forEvent(SaveDataSelectionHandler.HANDLER_NAME, payload).resolvingThrough(resolver).build();
    }
}
