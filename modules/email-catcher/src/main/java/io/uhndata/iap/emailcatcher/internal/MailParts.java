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
 * Assembles the MIME parts of a message from the text, HTML and attachments that {@link CaughtMessageBuilder}
 * collected.
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
     * An attached or inline part, held as given until the message is built.
     *
     * @param content the bytes of the part
     * @param type its MIME type, such as {@code image/png}
     * @param name the file name of an attachment, or the content id of an inline part
     * @param headers the headers to set on the part itself; empty for none
     * @param disposition {@link Part#ATTACHMENT} or {@link Part#INLINE}
     * @since 0.1.0
     */
    record Attachment(byte[] content, String type, String name, Collection<Header> headers, String disposition)
    {
        // The content array is the caller's own, and is written out unchanged
    }

    /**
     * One attached or inline part, as MIME.
     *
     * @param attachment the part as the caller gave it
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
            // An HTML body refers to the part as cid:<name>
            part.setContentID("<" + attachment.name() + ">");
        }
        for (final Header header : attachment.headers()) {
            part.setHeader(header.getName(), header.getValue());
        }
        return part;
    }

    /**
     * Both bodies as a {@code multipart/alternative}, plain text first. A mail client shows the last part it can
     * display.
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
     * @param text the plain text body; {@code null} if there is none
     * @param html the HTML body; {@code null} if there is none
     * @return a {@code multipart/alternative} part if there are both bodies, the one body if there is one, or an
     *         empty text part if there is neither
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
     * The body and the attached or inline parts, as one {@code multipart/related}.
     *
     * @param text the plain text body; {@code null} if there is none
     * @param html the HTML body; {@code null} if there is none
     * @param attachments the attached and inline parts; empty if there are none
     * @return the multipart to set on the message; {@code null} if there are no attached or inline parts
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
