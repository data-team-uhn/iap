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

import javax.jcr.version.VersionManager;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SelectionPartCreator}: which data requirements get a part, and what the part holds.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SelectionPartCreatorTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private SelectionPartCreator creator;

    private SelectionFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = SelectionFixture.installIn(this.context);
        this.creator = this.context.registerInjectActivateService(new SelectionPartCreator());
    }

    @Test
    void givesADataRequirementThatAppliesAnEmptyUnboundSelection() throws Exception
    {
        this.creator.createMissing(this.fixture.writableSubmission());

        assertEquals(1, this.fixture.selections().size());
        final var selection = this.fixture.selections().get(0).getValueMap();
        assertTrue(selection.containsKey("fulfills"));
        // Bound by the first save, not by being made
        assertFalse(selection.containsKey("catalogueVersion"));
        assertFalse(selection.containsKey("fields"));
    }

    @Test
    void leavesARequirementThatAlreadyHasOneAlone() throws Exception
    {
        this.fixture.select(SelectionFixture.BIRTH_DATE);

        this.creator.createMissing(this.fixture.writableSubmission());

        assertEquals(1, this.fixture.selections().size());
    }

    @Test
    void checksOutTheRequestBeforeGivingItAPart() throws Exception
    {
        final VersionManager versions = this.fixture.checkIn();
        Mockito.doAnswer(invocation -> {
            assertTrue(this.fixture.selections().isEmpty(), "A part was made before the request was checked out");
            return null;
        }).when(versions).checkout(SelectionFixture.SUBMISSION_PATH);

        this.creator.createMissing(this.fixture.writableSubmission());

        Mockito.verify(versions).checkout(SelectionFixture.SUBMISSION_PATH);
        assertEquals(1, this.fixture.selections().size());
    }

    @Test
    void makesNothingForARequirementThatDoesNotApply() throws Exception
    {
        this.fixture.hide(SelectionFixture.DATA);

        this.creator.createMissing(this.fixture.writableSubmission());

        assertTrue(this.fixture.selections().isEmpty());
    }
}
