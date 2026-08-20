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
package io.uhndata.iap.emailnotifications.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link InlineAttachment}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class InlineAttachmentTest
{
    @Test
    void keepsWhatItWasGiven()
    {
        final InlineAttachment attachment = new InlineAttachment("logo.png", "image/png", new byte[] { 1, 2 });

        assertEquals("logo.png", attachment.getName());
        assertEquals("image/png", attachment.getMimeType());
        assertArrayEquals(new byte[] { 1, 2 }, attachment.getContent());
    }

    @Test
    void anAttachmentWithNoContentIsEmptyRatherThanBroken()
    {
        assertArrayEquals(new byte[0], new InlineAttachment("empty.txt", "text/plain", null).getContent());
    }

    @Test
    void theContentCannotBeChangedFromOutside()
    {
        final byte[] original = { 1, 2 };
        final InlineAttachment attachment = new InlineAttachment("logo.png", "image/png", original);

        original[0] = 9;
        attachment.getContent()[1] = 9;

        assertArrayEquals(new byte[] { 1, 2 }, attachment.getContent());
    }

    @Test
    void describesItselfForLogging()
    {
        assertEquals("logo.png (image/png, 2 bytes)",
            new InlineAttachment("logo.png", "image/png", new byte[] { 1, 2 }).toString());
    }
}
