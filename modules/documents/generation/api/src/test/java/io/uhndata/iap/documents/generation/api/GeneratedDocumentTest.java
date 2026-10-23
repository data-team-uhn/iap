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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests for {@link GeneratedDocument}: nothing outside can change the bytes once the document was made.
 *
 * @version $Id$
 * @since 0.1.0
 */
class GeneratedDocumentTest
{
    @Test
    void theBytesCannotBeChangedFromOutside()
    {
        final byte[] pdf = { 1, 2, 3 };
        final GeneratedDocument document = new GeneratedDocument(new byte[] { 9 }, pdf);

        pdf[0] = 0;
        document.getPdf()[1] = 0;
        document.getDocx()[0] = 0;

        assertArrayEquals(new byte[] { 1, 2, 3 }, document.getPdf());
        assertArrayEquals(new byte[] { 9 }, document.getDocx());
    }
}
