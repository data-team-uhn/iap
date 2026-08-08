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
package io.uhndata.iap.workflows.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the {@link WorkflowException} hierarchy: each type carries its message, and its cause where one
 * makes sense.
 *
 * @version $Id$
 * @since 0.1.0
 */
class WorkflowExceptionsTest
{
    @Test
    void carryTheirMessage()
    {
        final List<WorkflowException> plain = List.of(
            new NoApplicableWorkflowException("nothing waiting"),
            new NotAuthorizedException("not allowed"),
            new InvalidPayloadException("bad data"),
            new WorkflowDefinitionException("broken definition"),
            new WorkflowFailedException("machinery failure"));

        assertEquals(List.of("nothing waiting", "not allowed", "bad data", "broken definition",
            "machinery failure"), plain.stream().map(WorkflowException::getMessage).toList());
        plain.forEach(exception -> assertNull(exception.getCause()));
    }

    @Test
    void carryTheirCause()
    {
        final Exception cause = new IllegalStateException("root");

        final List<WorkflowException> chained = List.of(
            new NotAuthorizedException("not allowed", cause),
            new InvalidPayloadException("bad data", cause),
            new WorkflowFailedException("machinery failure", cause));

        chained.forEach(exception -> assertSame(cause, exception.getCause()));
    }
}
