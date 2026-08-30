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

import java.util.Collection;
import java.util.List;

import jakarta.activation.DataHandler;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Assembles the MIME parts of a message.
 *
 * <p>
 * Everything here turns what a caller said into the parts carrying it, and nothing here decides what a caller
 * meant. That split is why it is a class of its own: {@link CaughtMessageBuilder} accumulates strings and bytes
 * as they arrive, and only when the message is built does any of it become MIME — so a caller adding an
 * attachment cannot be handed a MIME failure at a point where the interface gives it nowhere to go.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class MailParts
{
    private MailParts()
    {
        // Prevent instantiation of a utility class
    }

    /**
     * What a caller attached, held as given until the message is built.
     *
     * @param content the bytes of the part
     * @param type its MIME type
     * @param name the file name for an attachment, or the content id for an inline part
     * @param headers any headers to set on the part itself, never {@code null}
     * @param disposition {@link Part#ATTACHMENT} or {@link Part#INLINE}
     * @since 0.1.0
     */
    record Attachment(byte[] content, String type, String name, Collection<Header> headers, String disposition)
    {
        // A record only for holding what was given; the arrays it carries are the caller's own bytes, which are
        // written out unchanged
    }

    /**
     * One attached or inline part.
     *
     * @param attachment what the caller attached
     * @return the part, ready to be added to a multipart
     * @throws MessagingException if the part cannot be assembled
     */
    static MimeBodyPart of(final Attachment attachment) throws MessagingException
    {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment.content(), attachment.type())));
        part.setFileName(attachment.name());
        part.setDisposition(attachment.disposition());
        if (Part.INLINE.equals(attachment.disposition())) {
            // What an HTML body refers to the part by, as cid:<name>
            part.setContentID("<" + attachment.name() + ">");
        }
        for (final Header header : attachment.headers()) {
            part.setHeader(header.getName(), header.getValue());
        }
        return part;
    }

    /**
     * Both bodies as a {@code multipart/alternative}, least capable first, which is the order a client reads to
     * find the best part it can show.
     *
     * @param text the plain text body
     * @param html the HTML body
     * @return the two bodies as one multipart
     * @throws MessagingException if they cannot be assembled
     */
    static MimeMultipart alternative(final String text, final String html) throws MessagingException
    {
        final MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text(text));
        alternative.addBodyPart(html(html));
        return alternative;
    }

    /**
     * The body as one part, for a message that also carries attached or inline parts.
     *
     * @param text the plain text body, or {@code null}
     * @param html the HTML body, or {@code null}
     * @return the body part
     * @throws MessagingException if it cannot be assembled
     */
    static MimeBodyPart body(final String text, final String html) throws MessagingException
    {
        if (text != null && html != null) {
            final MimeBodyPart both = new MimeBodyPart();
            both.setContent(alternative(text, html));
            return both;
        }
        return html != null ? html(html) : text(text == null ? "" : text);
    }

    /**
     * Everything a message is made of, in the shape its parts call for: the body alone when nothing is attached,
     * and a {@code multipart/related} wrapping the body and the parts when something is.
     *
     * @param text the plain text body, or {@code null}
     * @param html the HTML body, or {@code null}
     * @param attachments what was attached, possibly none
     * @return the multipart to set on the message, or {@code null} when the body should be the message itself
     * @throws MessagingException if the parts cannot be assembled
     */
    static MimeMultipart related(final String text, final String html, final List<Attachment> attachments)
        throws MessagingException
    {
        if (attachments.isEmpty()) {
            return null;
        }
        final MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(body(text, html));
        for (final Attachment attachment : attachments) {
            related.addBodyPart(of(attachment));
        }
        return related;
    }

    private static MimeBodyPart text(final String value) throws MessagingException
    {
        final MimeBodyPart part = new MimeBodyPart();
        part.setText(value, "UTF-8");
        return part;
    }

    private static MimeBodyPart html(final String value) throws MessagingException
    {
        final MimeBodyPart part = new MimeBodyPart();
        part.setContent(value, "text/html; charset=UTF-8");
        return part;
    }
}
