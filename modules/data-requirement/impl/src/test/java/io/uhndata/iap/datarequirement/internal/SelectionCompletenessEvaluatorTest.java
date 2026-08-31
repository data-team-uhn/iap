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

import java.lang.reflect.Field;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SelectionCompletenessEvaluator}: when a data requirement is still missing something, and
 * what carries the mark.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SelectionCompletenessEvaluatorTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SelectionCompletenessEvaluator evaluator = new SelectionCompletenessEvaluator();

    private SelectionFixture fixture;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.fixture = SelectionFixture.installIn(this.context);
        // DS metadata only exists in the packaged bundle, so the reference is set by reflection
        final Field conditions = SelectionCompletenessEvaluator.class.getDeclaredField("conditions");
        conditions.setAccessible(true);
        conditions.set(this.evaluator, this.fixture.conditions());
    }

    @Test
    void marksASelectionWithNothingChosen()
    {
        final Resource selection = this.fixture.select();

        assertEquals(List.of(selection.getPath()), paths(this.evaluator.evaluate(this.fixture.submission())));
    }

    @Test
    void passesASelectionWithAFieldChosen()
    {
        this.fixture.select("records/Patient/birthDate");

        assertEquals(List.of(), this.evaluator.evaluate(this.fixture.submission()));
    }

    // With no part to carry the mark, the submission carries it
    @Test
    void marksTheSubmissionWhenTheRequirementHasNoSelection()
    {
        assertEquals(List.of(SelectionFixture.SUBMISSION_PATH),
            paths(this.evaluator.evaluate(this.fixture.submission())));
    }

    @Test
    void passesARequirementThatDoesNotApply()
    {
        this.fixture.hide(SelectionFixture.DATA);

        assertEquals(List.of(), this.evaluator.evaluate(this.fixture.submission()));
    }

    private static List<String> paths(final List<Resource> resources)
    {
        return resources.stream().map(Resource::getPath).toList();
    }
}
