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
import java.util.Optional;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.submissions.spi.AnswerConstraint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link AnswerConstraintsValidator}: the first refusal, in the order the constraints came in, is heard.
 *
 * <p>The order itself is the framework's: it keeps the bound list sorted by ascending {@code service.ranking}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerConstraintsValidatorTest
{
    private final SlingContext context = ConstraintFixture.newContext();

    @Test
    void acceptsWhenThereIsNoConstraint() throws Exception
    {
        assertNull(ConstraintFixture.showingEverythingTo(this.context).verdictOn(Map.of(), "anything"));
    }

    @Test
    void acceptsWhenEveryConstraintAccepts() throws Exception
    {
        assertNull(ConstraintFixture.showingEverythingTo(this.context, accepting(), accepting())
            .verdictOn(Map.of(), "anything"));
    }

    @Test
    void hearsTheFirstRefusalInTheOrderTheConstraintsCameIn() throws Exception
    {
        assertEquals("first", ConstraintFixture.showingEverythingTo(this.context, refusing("first"),
            refusing("second")).verdictOn(Map.of(), "anything"));
    }

    @Test
    void passesOverConstraintsThatAccept() throws Exception
    {
        assertEquals("second", ConstraintFixture.showingEverythingTo(this.context, accepting(), refusing("second"))
            .verdictOn(Map.of(), "anything"));
    }

    private static AnswerConstraint accepting()
    {
        return (question, values) -> Optional.empty();
    }

    private static AnswerConstraint refusing(final String reason)
    {
        return (question, values) -> Optional.of(reason);
    }
}
