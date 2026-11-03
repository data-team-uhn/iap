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
package io.uhndata.iap.documents.generation.api;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.documents.generation.api.GenerationResult.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GenerationResult}: a generated result always has its document, and a failed one never does.
 *
 * @version $Id$
 * @since 0.1.0
 */
class GenerationResultTest
{
    @Test
    void aGeneratedResultCarriesItsDocumentAndNoMessage()
    {
        final GeneratedDocument document = new GeneratedDocument(new byte[] { 1 }, new byte[] { 2 });
        final GenerationResult result = GenerationResult.generated(document);

        assertEquals(Status.GENERATED, result.getStatus());
        assertSame(document, result.getDocument());
        assertEquals("", result.getMessage());
        assertEquals("GENERATED", result.toString());
    }

    @Test
    void aFailedResultCarriesItsMessageAndNoDocument()
    {
        final GenerationResult result = GenerationResult.failed(Status.UNAVAILABLE, "worker busy");

        assertNull(result.getDocument());
        assertEquals("UNAVAILABLE: worker busy", result.toString());
    }

    @Test
    void aGeneratedResultCannotBeMadeWithoutItsDocument()
    {
        assertThrows(IllegalArgumentException.class, () -> GenerationResult.failed(Status.GENERATED, "done"));
    }
}
