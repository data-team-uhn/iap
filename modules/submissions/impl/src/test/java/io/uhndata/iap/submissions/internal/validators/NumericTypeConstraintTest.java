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
package io.uhndata.iap.submissions.internal.validators;

import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link NumericTypeConstraint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class NumericTypeConstraintTest
{
    private static final String DATA_TYPE = "dataType";

    private static final Map<String, Object> WHOLE = Map.of(DATA_TYPE, "long");

    private static final Map<String, Object> DECIMAL = Map.of(DATA_TYPE, "double");

    private final SlingContext context = ConstraintFixture.newContext();

    private ConstraintFixture fixture;

    @BeforeEach
    void setUp()
    {
        this.fixture = ConstraintFixture.showingEverything(this.context);
    }

    @Test
    void acceptsAWholeNumberForALongQuestion() throws Exception
    {
        assertNull(this.fixture.verdictOn(WHOLE, "12"));
    }

    @Test
    void refusesAFractionForALongQuestion() throws Exception
    {
        assertEquals("The answer to \"The question\" must be a whole number.", this.fixture.verdictOn(WHOLE, "1.5"));
    }

    @Test
    void acceptsAFractionForADoubleQuestion() throws Exception
    {
        assertNull(this.fixture.verdictOn(DECIMAL, "1.5"));
    }

    @Test
    void refusesTextForANumericQuestion() throws Exception
    {
        assertEquals("The answer to \"The question\" must be a number.", this.fixture.verdictOn(DECIMAL, "soon"));
    }

    @Test
    void refusesANumberThatIsNotFinite() throws Exception
    {
        assertEquals("The answer to \"The question\" must be a number.", this.fixture.verdictOn(DECIMAL, "NaN"));
    }

    @Test
    void reportsTheTypeBeforeTheRange() throws Exception
    {
        assertEquals("The answer to \"The question\" must be a number.",
            this.fixture.verdictOn(Map.of(DATA_TYPE, "double", "minValue", 1.0d), "soon"));
    }

    @Test
    void leavesOtherTypesAlone() throws Exception
    {
        assertNull(this.fixture.verdictOn(Map.of(DATA_TYPE, "text"), "soon"));
    }
}
