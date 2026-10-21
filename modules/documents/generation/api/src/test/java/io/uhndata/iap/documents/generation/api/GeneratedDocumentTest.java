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

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GeneratedDocument}: the hash is of the PDF as received, and nothing outside can change the
 * bytes that hash vouches for.
 *
 * @version $Id$
 * @since 0.1.0
 */
class GeneratedDocumentTest
{
    @Test
    void theHashIsThePdfsSha256()
    {
        final GeneratedDocument document = new GeneratedDocument(new byte[] { 1 },
            "abc".getBytes(StandardCharsets.UTF_8));

        // The published SHA-256 test vector for "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", document.getPdfSha256());
    }

    @Test
    void theBytesCannotBeChangedAfterTheHashWasTaken()
    {
        final byte[] pdf = { 1, 2, 3 };
        final GeneratedDocument document = new GeneratedDocument(new byte[] { 9 }, pdf);

        pdf[0] = 0;
        document.getPdf()[1] = 0;
        document.getDocx()[0] = 0;

        assertArrayEquals(new byte[] { 1, 2, 3 }, document.getPdf(), "the PDF must be the one that was hashed");
        assertArrayEquals(new byte[] { 9 }, document.getDocx());
    }

    @Test
    void aMissingAlgorithmIsAnIllegalState()
    {
        assertThrows(IllegalStateException.class, () -> GeneratedDocument.digest(new byte[0], "NO-SUCH-DIGEST"));
    }
}
