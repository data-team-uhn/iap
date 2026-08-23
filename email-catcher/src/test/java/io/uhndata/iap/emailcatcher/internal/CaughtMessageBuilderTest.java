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
package io.uhndata.iap.emailcatcher.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CaughtMessageBuilder}: that the message it builds says what the caller asked for, in every
 * shape the interface offers for asking.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CaughtMessageBuilderTest
{
    private static final String ADDRESS = "someone@example.com";

    private static final String OTHER = "other@example.com";

    private final CaughtMessageBuilder builder = new CaughtMessageBuilder();

    private static String[] recipients(final MimeMessage message, final RecipientType type)
        throws MessagingException
    {
        final var addresses = message.getRecipients(type);
        if (addresses == null) {
            return new String[0];
        }
        return java.util.Arrays.stream(addresses).map(Object::toString).toArray(String[]::new);
    }

    @Test
    void buildsAPlainTextMessage() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS, "Someone")
            .to(OTHER, "Other")
            .subject("A subject")
            .text("A body")
            .build();

        assertEquals("A subject", message.getSubject());
        assertEquals("Someone <" + ADDRESS + ">", message.getFrom()[0].toString());
        assertEquals("Other <" + OTHER + ">", recipients(message, RecipientType.TO)[0]);
        assertEquals("A body", message.getContent());
        assertTrue(message.isMimeType("text/plain"));
    }

    @Test
    void buildsAnHtmlOnlyMessage() throws Exception
    {
        final MimeMessage message = this.builder.from(ADDRESS).to(OTHER).html("<p>Hello</p>").build();

        assertTrue(message.isMimeType("text/html"));
        assertEquals("<p>Hello</p>", message.getContent());
    }

    // Least capable first, which is the order a client reads to find the best part it can show
    @Test
    void offersPlainTextBeforeHtmlWhenBothAreGiven() throws Exception
    {
        final MimeMessage message = this.builder.from(ADDRESS).to(OTHER).text("Plain").html("<p>Rich</p>").build();

        assertTrue(message.isMimeType("multipart/alternative"));
        final Multipart parts = (Multipart) message.getContent();
        assertEquals(2, parts.getCount());
        assertEquals("Plain", parts.getBodyPart(0).getContent());
        assertEquals("<p>Rich</p>", parts.getBodyPart(1).getContent());
    }

    // A message with nothing in it is still a message; it must not fail to build
    @Test
    void buildsAMessageWithNoBodyAtAll() throws Exception
    {
        assertEquals("", this.builder.from(ADDRESS).to(OTHER).build().getContent());
    }

    // saveChanges settles the headers and stamps a Date among them, which is what the catcher records as the
    // moment the message was handed over
    @Test
    void carriesTheMomentItWasBuilt() throws Exception
    {
        assertNotNull(this.builder.from(ADDRESS).to(OTHER).text("x").build().getSentDate());
    }

    @Test
    void carriesEveryKindOfRecipient() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .cc("cc@example.com")
            .bcc("bcc@example.com")
            .replyTo("reply@example.com")
            .text("x")
            .build();

        assertArrayEquals(new String[] { OTHER }, recipients(message, RecipientType.TO));
        assertArrayEquals(new String[] { "cc@example.com" }, recipients(message, RecipientType.CC));
        assertArrayEquals(new String[] { "bcc@example.com" }, recipients(message, RecipientType.BCC));
        assertEquals("reply@example.com", message.getReplyTo()[0].toString());
    }

    // The same address, offered in each of the six shapes the interface takes it in
    @Test
    void takesRecipientsInEveryShapeOffered() throws Exception
    {
        final InternetAddress address = new InternetAddress(ADDRESS);
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(address)
            .to("a@example.com")
            .to("b@example.com", "B")
            .to(new InternetAddress[] { new InternetAddress("c@example.com") })
            .to(new String[] { "d@example.com" })
            .to(List.of("e@example.com"))
            .text("x")
            .build();

        assertArrayEquals(
            new String[] { ADDRESS, "a@example.com", "B <b@example.com>", "c@example.com", "d@example.com",
                "e@example.com" },
            recipients(message, RecipientType.TO));
    }

    @Test
    void takesCopiesInEveryShapeOffered() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .cc(new InternetAddress("a@example.com"))
            .cc("b@example.com")
            .cc("c@example.com", "C")
            .cc(new InternetAddress[] { new InternetAddress("d@example.com") })
            .cc(new String[] { "e@example.com" })
            .cc(List.of("f@example.com"))
            .text("x")
            .build();

        assertEquals(6, recipients(message, RecipientType.CC).length);
    }

    @Test
    void takesBlindCopiesInEveryShapeOffered() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .bcc(new InternetAddress("a@example.com"))
            .bcc("b@example.com")
            .bcc("c@example.com", "C")
            .bcc(new InternetAddress[] { new InternetAddress("d@example.com") })
            .bcc(new String[] { "e@example.com" })
            .bcc(List.of("f@example.com"))
            .text("x")
            .build();

        assertEquals(6, recipients(message, RecipientType.BCC).length);
    }

    @Test
    void takesReplyToInEveryShapeOffered() throws Exception
    {
        final MimeMessage message = this.builder
            .from(new InternetAddress(ADDRESS))
            .to(OTHER)
            .replyTo(new InternetAddress("a@example.com"))
            .replyTo("b@example.com")
            .replyTo("c@example.com", "C")
            .replyTo(new InternetAddress[] { new InternetAddress("d@example.com") })
            .replyTo(new String[] { "e@example.com" })
            .replyTo(List.of("f@example.com"))
            .text("x")
            .build();

        assertEquals(6, message.getReplyTo().length);
    }

    @Test
    void carriesHeadersGivenOneByOneAndInBulk() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .header("X-One", "1")
            .headers(List.of(new Header("X-Two", "2"), new Header("X-Three", "3")))
            .text("x")
            .build();

        assertEquals("1", message.getHeader("X-One")[0]);
        assertEquals("2", message.getHeader("X-Two")[0]);
        assertEquals("3", message.getHeader("X-Three")[0]);
    }

    @Test
    void wrapsTheBodyWhenSomethingIsAttached() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .text("A body")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build();

        assertTrue(message.isMimeType("multipart/related"));
        final Multipart parts = (Multipart) message.getContent();
        assertEquals(2, parts.getCount());
        assertEquals("A body", parts.getBodyPart(0).getContent());
        assertEquals("note.txt", parts.getBodyPart(1).getFileName());
    }

    // An HTML body refers to an inline part by cid:<id>, which is what makes the content id load-bearing
    @Test
    void givesAnInlinePartTheContentIdItsBodyRefersTo() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .html("<img src=\"cid:logo.png\"/>")
            .inline("png".getBytes(StandardCharsets.UTF_8), "image/png", "logo.png")
            .build();

        final Multipart parts = (Multipart) message.getContent();
        assertEquals("<logo.png>", parts.getBodyPart(1).getHeader("Content-ID")[0]);
    }

    @Test
    void carriesHeadersSetOnAPartItself() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .text("x")
            .inline("png".getBytes(StandardCharsets.UTF_8), "image/png", "logo.png",
                List.of(new Header("Content-Disposition", "inline; filename=\"logo.png\"")))
            .build();

        final Multipart parts = (Multipart) message.getContent();
        assertTrue(parts.getBodyPart(1).getHeader("Content-Disposition")[0].contains("logo.png"));
    }

    // Both bodies plus an attachment: the alternative pair becomes one part of the related whole
    @Test
    void keepsBothBodiesTogetherInsideAnAttachedMessage() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .text("Plain")
            .html("<p>Rich</p>")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build();

        final Multipart related = (Multipart) message.getContent();
        assertEquals(2, related.getCount());
        final Multipart alternative = (Multipart) related.getBodyPart(0).getContent();
        assertEquals(2, alternative.getCount());
    }

    @Test
    void keepsAnHtmlOnlyBodyWhenSomethingIsAttached() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .html("<p>Rich</p>")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build();

        final Multipart related = (Multipart) message.getContent();
        assertEquals("<p>Rich</p>", related.getBodyPart(0).getContent());
    }

    // UTF-8 is required of every JVM, so the caller never sees this; it is still the honest answer for a name
    // that cannot be encoded, because the alternative is a message that silently loses its sender
    @Test
    void refusesAnAddressWhoseNameCannotBeEncoded()
    {
        assertThrows(AddressException.class,
            () -> CaughtMessageBuilder.address(ADDRESS, "Zoë", "no-such-charset"));
    }

    // An attachment and nothing else: the body part is empty rather than absent, so the message stays well formed
    @Test
    void wrapsAnEmptyBodyWhenOnlyAnAttachmentIsGiven() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build();

        final Multipart parts = (Multipart) message.getContent();
        assertEquals("", parts.getBodyPart(0).getContent());
    }

    @Test
    void refusesAnAddressThatIsNotOne()
    {
        assertThrows(AddressException.class, () -> this.builder.to("not an address"));
    }

    // The interface lets the header collection be left out altogether
    @Test
    void acceptsAPartWithNoHeadersOfItsOwn() throws Exception
    {
        final MimeMessage message = this.builder
            .from(ADDRESS)
            .to(OTHER)
            .text("x")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt", null)
            .build();

        assertEquals(2, ((Multipart) message.getContent()).getCount());
    }

    // Attaching only remembers, which is the point of holding the bytes rather than assembling a part: these
    // methods cannot report a MIME failure, so they are arranged not to have one to report
    @Test
    void attachingOnlyRemembers()
    {
        assertSame(this.builder,
            this.builder.attachment("x".getBytes(StandardCharsets.UTF_8), "text/plain", "n.txt"));
        assertSame(this.builder, this.builder.inline("x".getBytes(StandardCharsets.UTF_8), "image/png", "l.png"));
    }

    @Test
    void encodesADisplayNameThatNeedsIt() throws Exception
    {
        final MimeMessage message = this.builder.from(ADDRESS, "Zoë").to(OTHER).text("x").build();

        // Encoded per RFC 2047 rather than sent as raw bytes, and decoding gives back what was asked for
        assertEquals("Zoë", ((InternetAddress) message.getFrom()[0]).getPersonal());
    }

}
