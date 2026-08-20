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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Email}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class EmailTest
{
    @Test
    void carriesTheRecipientBodiesAndHeaders()
    {
        final Email email = template().getEmailBuilder()
            .withBody("<p>html</p>", "text")
            .withRecipient("alice@example.invalid", "Alice")
            .withExtraHeader("Auto-Submitted", "auto-generated")
            .withExtraHeader("X-Origin", "IAP")
            .build();

        assertEquals("alice@example.invalid", email.getRecipientAddress());
        assertEquals("Alice", email.getRecipientName());
        assertEquals("<p>html</p>", email.getHtmlBody());
        assertEquals("text", email.getTextBody());
        assertEquals(List.of("Auto-Submitted", "X-Origin"), List.copyOf(email.getExtraHeaders().keySet()));
    }

    @Test
    void keepsWhatItInheritedFromItsTemplate()
    {
        final Email email = template().getEmailBuilder()
            .withBody(null, "text")
            .withRecipient("alice@example.invalid", null)
            .build();

        assertEquals("noreply@example.invalid", email.getSenderAddress());
        assertEquals("IAP", email.getSenderName());
        assertEquals(1, email.getInlineAttachments().size());
        assertNull(email.getRecipientName());
    }

    @Test
    void anEmailNeedsARecipientAddress()
    {
        final Email.Builder builder = template().getEmailBuilder().withBody(null, "text");

        assertEquals("The email has no recipient address",
            assertThrows(IllegalStateException.class, builder::build).getMessage());
    }

    @Test
    void anEmailNeedsAtLeastOneBody()
    {
        final Email.Builder builder =
            template().getEmailBuilder().withRecipient("alice@example.invalid", null);

        assertEquals("The email has neither an HTML nor a plain text body",
            assertThrows(IllegalStateException.class, builder::build).getMessage());
    }

    @Test
    void theSubjectFallsBackToTheTemplatesOwn()
    {
        final Email fromTemplate = template().getEmailBuilder()
            .withBody(null, "text").withRecipient("alice@example.invalid", null).build();
        final Email substituted = template().getEmailBuilder(Map.of("who", "Alice"))
            .withBody(null, "text").withRecipient("alice@example.invalid", null).build();

        assertEquals("Hello ${who}", fromTemplate.getSubject());
        assertEquals("Hello Alice", substituted.getSubject());
    }

    @Test
    void theHeadersCannotBeChangedFromOutside()
    {
        final Email email = template().getEmailBuilder()
            .withBody(null, "text")
            .withRecipient("alice@example.invalid", null)
            .withExtraHeader("X-Origin", "IAP")
            .build();

        email.getExtraHeaders().put("X-Injected", "nope");

        assertEquals(Map.of("X-Origin", "IAP"), email.getExtraHeaders());
    }

    private EmailTemplate template()
    {
        return EmailTemplate.builder()
            .withSenderAddress("noreply@example.invalid")
            .withSenderName("IAP")
            .withSubject("Hello ${who}")
            .withInlineAttachment("logo.png", "image/png", new byte[] { 1 })
            .build();
    }
}
