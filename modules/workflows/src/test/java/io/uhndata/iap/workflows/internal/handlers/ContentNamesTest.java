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
package io.uhndata.iap.workflows.internal.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ContentNames}: the names content takes after what it says, which the frontend's
 * {@code suggestName} tests hold to the same examples, and the names it may be given.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ContentNamesTest
{
    @Test
    void namesContentAfterTheFirstWordsOfWhatItSaysWithoutAccents()
    {
        assertEquals("yourDateOfBirthAs", ContentNames.fromText("Your date of birth, as on your passport"));
        assertEquals("ageALEntree", ContentNames.fromText("Âge à l'entrée"));
        assertEquals("2Things", ContentNames.fromText("2 things"));
        assertEquals("возраст", ContentNames.fromText("Возраст"));
        assertEquals("", ContentNames.fromText(" - "));
    }

    @Test
    void allowsWhatANodeCanBeCalledAndThePatternAccepts()
    {
        assertTrue(ContentNames.allowed("yourAge", null));
        assertTrue(ContentNames.allowed("yourAge", "^[a-zA-Z]+$"));
        assertFalse(ContentNames.allowed("your age", "^[a-zA-Z]+$"));
        assertFalse(ContentNames.allowed("a/b", null));
        assertFalse(ContentNames.allowed("..", null));
    }
}
