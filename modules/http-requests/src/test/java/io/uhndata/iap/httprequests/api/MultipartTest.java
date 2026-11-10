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
package io.uhndata.iap.httprequests.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Multipart}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class MultipartTest
{
    @Test
    void keepsThePartsInTheOrderTheyWereAdded() throws IOException
    {
        final Multipart body = new Multipart()
            .add("manifest", "{\"é\":1}", "application/json")
            .add("b0", "scan.pdf", "application/pdf", InputStream::nullInputStream);

        final List<Multipart.Part> parts = body.getParts();
        assertEquals(List.of("manifest", "b0"), parts.stream().map(Multipart.Part::name).toList());
        assertNull(parts.get(0).filename());
        assertEquals("scan.pdf", parts.get(1).filename());
        try (InputStream value = parts.get(0).content().open()) {
            assertEquals("{\"é\":1}", StandardCharsets.UTF_8.decode(ByteBuffer.wrap(value.readAllBytes())).toString());
        }
    }

    @Test
    void theListOfPartsCannotBeChangedBehindItsBack()
    {
        final List<Multipart.Part> parts = new Multipart().add("a", "b", "text/plain").getParts();

        assertThrows(UnsupportedOperationException.class, parts::clear);
    }

    @Test
    void aContentTypeCannotAddHeadersOfItsOwn()
    {
        final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
            () -> new Multipart().add("a", "b", "text/plain\r\nX-Injected: yes"));

        assertTrue(refusal.getMessage().contains("control characters"));
    }
}
