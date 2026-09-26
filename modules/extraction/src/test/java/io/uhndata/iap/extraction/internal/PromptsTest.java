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
package io.uhndata.iap.extraction.internal;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Prompts}: that the bundle carries every prompt named on it, and says so plainly when it
 * does not.
 *
 * @version $Id$
 * @since 0.1.0
 */
class PromptsTest
{
    @Test
    void carriesTheFullRubricReference()
    {
        final String reference = Prompts.read(Prompts.PROTOCOL_STRUCTURE);

        assertTrue(reference.contains("B.1 General information"), "it opens at the first rubric");
        assertTrue(reference.contains("B.17 References and appendix"), "and runs to the last");
    }

    @Test
    void readsAPromptOnlyOnce()
    {
        assertEquals(Prompts.read(Prompts.IS_PROPOSAL_SYSTEM), Prompts.read(Prompts.IS_PROPOSAL_SYSTEM));
    }

    @Test
    void saysSoWhenTheBundleCarriesNoSuchPrompt()
    {
        final UncheckedIOException failure =
            assertThrows(UncheckedIOException.class, () -> Prompts.read("not_a_prompt.md"));

        assertTrue(failure.getMessage().contains("not_a_prompt.md"));
    }
}
