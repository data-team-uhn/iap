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

import java.lang.reflect.Constructor;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailUtils}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class EmailUtilsTest
{
    private final MailService mailService = mock(MailService.class);

    private final MessageBuilder message = mock(MessageBuilder.class, RETURNS_SELF);

    @BeforeEach
    void setUp()
    {
        // The mail implementation is not on the test classpath, so the built message stays an unusable stand-in;
        // what matters here is which builder calls were made, and that the result was handed to the service
        when(this.mailService.getMessageBuilder()).thenReturn(this.message);
    }

    @Test
    void fillsInThePlaceholdersOfATemplate()
    {
        assertEquals("Dear Alice, hello",
            EmailUtils.render("Dear ${name}, hello", Map.of("name", "Alice")));
    }

    @Test
    void anAbsentTemplateFillsInToNothing()
    {
        // A template may legitimately have no HTML part, or no text part
        assertNull(EmailUtils.render(null, Map.of()));
    }

    @Test
    void sendsAPlainTextEmail() throws MessagingException
    {
        EmailUtils.sendTextEmail(email("Bob"), this.mailService);

        verify(this.message).from("noreply@example.invalid", "IAP");
        verify(this.message).replyTo("noreply@example.invalid", "IAP");
        verify(this.message).subject("A subject");
        verify(this.message).text("plain text");
        verify(this.message).to("someone@example.invalid", "Bob");
        verify(this.message).header("Auto-Submitted", "auto-generated");
        verify(this.mailService).sendMessage(ArgumentMatchers.<MimeMessage>any());
        // A plain text email carries no HTML part and no inline images
        verify(this.message, never()).html(anyString());
    }

    @Test
    void sendsAnHtmlEmailWithItsInlineAttachments() throws MessagingException
    {
        EmailUtils.sendHtmlEmail(email("Bob"), this.mailService);

        verify(this.message).html("<p>rich text</p>");
        verify(this.message).text("plain text");
        verify(this.message).inline(any(byte[].class), any(), any(), any());
        verify(this.mailService).sendMessage(ArgumentMatchers.<MimeMessage>any());
    }

    @Test
    void aRecipientWithNoNameIsAddressedByAddressAlone() throws MessagingException
    {
        EmailUtils.sendHtmlEmail(email(null), this.mailService);

        // Passing a null display name alongside the address is not the same thing as having none
        verify(this.message).to("someone@example.invalid");
        verify(this.message, never()).to(anyString(), any());
    }

    @Test
    void aPlainTextRecipientWithNoNameIsAlsoAddressedByAddressAlone() throws MessagingException
    {
        EmailUtils.sendTextEmail(email(null), this.mailService);

        verify(this.message).to("someone@example.invalid");
        verify(this.message, never()).to(anyString(), any());
    }

    @Test
    void isAUtilityClass() throws ReflectiveOperationException
    {
        final Constructor<EmailUtils> constructor = EmailUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertNotNull(constructor.newInstance());
    }

    private Email email(final String recipientName)
    {
        final EmailTemplate template = EmailTemplate.builder()
            .withSenderAddress("noreply@example.invalid")
            .withSenderName("IAP")
            .withSubject("A subject")
            .withInlineAttachment("logo.png", "image/png", new byte[] { 1, 2, 3 })
            .build();
        return template.getEmailBuilder()
            .withBody("<p>rich text</p>", "plain text")
            .withRecipient("someone@example.invalid", recipientName)
            .withExtraHeader("Auto-Submitted", "auto-generated")
            .build();
    }
}
