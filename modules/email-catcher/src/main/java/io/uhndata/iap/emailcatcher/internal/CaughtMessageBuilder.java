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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.apache.sling.commons.messaging.mail.MessageBuilder;

/**
 * Builds the {@link MimeMessage} that the catcher's mail service files. The builder of Sling's
 * {@code SimpleMailService} is internal to its bundle, and the catcher must work where that service has no
 * configuration.
 *
 * <p>
 * Every recipient method ends in {@link #recipients(RecipientType, InternetAddress[])}, and every sender method in
 * {@link #from(InternetAddress)}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class CaughtMessageBuilder implements MessageBuilder
{
    /** A mail session for building messages. Nothing opens a transport from it. */
    private static final Session MAIL_SESSION = Session.getInstance(new Properties());

    private final List<Header> headers = new ArrayList<>();

    // Kept as strings, not as InternetAddress objects: an InternetAddress is mutable, and the caller still holds it
    private final List<String> to = new ArrayList<>();

    private final List<String> cc = new ArrayList<>();

    private final List<String> bcc = new ArrayList<>();

    private final List<String> replyTo = new ArrayList<>();

    private final List<MailParts.Attachment> parts = new ArrayList<>();

    private String sender;

    private String subject;

    private String text;

    private String html;

    @Override
    public MessageBuilder header(final String name, final String value)
    {
        this.headers.add(new Header(name, value));
        return this;
    }

    @Override
    public MessageBuilder headers(final Collection<Header> values)
    {
        this.headers.addAll(values);
        return this;
    }

    @Override
    public MessageBuilder from(final InternetAddress address)
    {
        this.sender = address.toString();
        return this;
    }

    @Override
    public MessageBuilder from(final String address) throws AddressException
    {
        return from(new InternetAddress(address));
    }

    @Override
    public MessageBuilder from(final String address, final String name) throws AddressException
    {
        return from(address(address, name));
    }

    @Override
    public MessageBuilder replyTo(final InternetAddress address)
    {
        this.replyTo.add(address.toString());
        return this;
    }

    @Override
    public MessageBuilder replyTo(final String address) throws AddressException
    {
        return replyTo(new InternetAddress(address));
    }

    @Override
    public MessageBuilder replyTo(final String address, final String name) throws AddressException
    {
        return replyTo(address(address, name));
    }

    @Override
    public MessageBuilder replyTo(final InternetAddress[] addresses)
    {
        Arrays.stream(addresses).map(InternetAddress::toString).forEach(this.replyTo::add);
        return this;
    }

    @Override
    public MessageBuilder replyTo(final String[] addresses) throws AddressException
    {
        return replyTo(parse(addresses));
    }

    @Override
    public MessageBuilder replyTo(final Collection<String> addresses) throws AddressException
    {
        return replyTo(addresses.toArray(new String[0]));
    }

    @Override
    public MessageBuilder to(final InternetAddress address)
    {
        return recipients(RecipientType.TO, new InternetAddress[] { address });
    }

    @Override
    public MessageBuilder to(final String address) throws AddressException
    {
        return to(new InternetAddress(address));
    }

    @Override
    public MessageBuilder to(final String address, final String name) throws AddressException
    {
        return to(address(address, name));
    }

    @Override
    public MessageBuilder to(final InternetAddress[] addresses)
    {
        return recipients(RecipientType.TO, addresses);
    }

    @Override
    public MessageBuilder to(final String[] addresses) throws AddressException
    {
        return to(parse(addresses));
    }

    @Override
    public MessageBuilder to(final Collection<String> addresses) throws AddressException
    {
        return to(addresses.toArray(new String[0]));
    }

    @Override
    public MessageBuilder cc(final InternetAddress address)
    {
        return recipients(RecipientType.CC, new InternetAddress[] { address });
    }

    @Override
    public MessageBuilder cc(final String address) throws AddressException
    {
        return cc(new InternetAddress(address));
    }

    @Override
    public MessageBuilder cc(final String address, final String name) throws AddressException
    {
        return cc(address(address, name));
    }

    @Override
    public MessageBuilder cc(final InternetAddress[] addresses)
    {
        return recipients(RecipientType.CC, addresses);
    }

    @Override
    public MessageBuilder cc(final String[] addresses) throws AddressException
    {
        return cc(parse(addresses));
    }

    @Override
    public MessageBuilder cc(final Collection<String> addresses) throws AddressException
    {
        return cc(addresses.toArray(new String[0]));
    }

    @Override
    public MessageBuilder bcc(final InternetAddress address)
    {
        return recipients(RecipientType.BCC, new InternetAddress[] { address });
    }

    @Override
    public MessageBuilder bcc(final String address) throws AddressException
    {
        return bcc(new InternetAddress(address));
    }

    @Override
    public MessageBuilder bcc(final String address, final String name) throws AddressException
    {
        return bcc(address(address, name));
    }

    @Override
    public MessageBuilder bcc(final InternetAddress[] addresses)
    {
        return recipients(RecipientType.BCC, addresses);
    }

    @Override
    public MessageBuilder bcc(final String[] addresses) throws AddressException
    {
        return bcc(parse(addresses));
    }

    @Override
    public MessageBuilder bcc(final Collection<String> addresses) throws AddressException
    {
        return bcc(addresses.toArray(new String[0]));
    }

    @Override
    public MessageBuilder subject(final String value)
    {
        this.subject = value;
        return this;
    }

    @Override
    public MessageBuilder text(final String value)
    {
        this.text = value;
        return this;
    }

    @Override
    public MessageBuilder html(final String value)
    {
        this.html = value;
        return this;
    }

    @Override
    public MessageBuilder attachment(final byte[] content, final String type, final String name)
    {
        return attachment(content, type, name, List.of());
    }

    @Override
    public MessageBuilder attachment(final byte[] content, final String type, final String name,
        final Collection<Header> partHeaders)
    {
        return part(content, type, name, headersOr(partHeaders), Part.ATTACHMENT);
    }

    @Override
    public MessageBuilder inline(final byte[] content, final String type, final String id)
    {
        return inline(content, type, id, List.of());
    }

    @Override
    public MessageBuilder inline(final byte[] content, final String type, final String id,
        final Collection<Header> partHeaders)
    {
        return part(content, type, id, headersOr(partHeaders), Part.INLINE);
    }

    @Override
    public MimeMessage build() throws MessagingException
    {
        final MimeMessage message = new MimeMessage(MAIL_SESSION);
        if (this.sender != null) {
            message.setFrom(new InternetAddress(this.sender));
        }
        if (!this.replyTo.isEmpty()) {
            message.setReplyTo(parse(this.replyTo.toArray(new String[0])));
        }
        setRecipients(message, RecipientType.TO, this.to);
        setRecipients(message, RecipientType.CC, this.cc);
        setRecipients(message, RecipientType.BCC, this.bcc);
        if (this.subject != null) {
            message.setSubject(this.subject, "UTF-8");
        }
        for (final Header value : this.headers) {
            message.addHeader(value.getName(), value.getValue());
        }
        setBody(message);
        // Sets the headers that depend on the content, and a Date header if there is none
        message.saveChanges();
        return message;
    }

    /**
     * Puts the bodies and parts given onto the message. A message with no body at all gets an empty text body.
     *
     * @param message the message being built
     * @throws MessagingException if the parts cannot be assembled
     */
    private void setBody(final MimeMessage message) throws MessagingException
    {
        final MimeMultipart related = MailParts.related(this.text, this.html, this.parts);
        if (related != null) {
            message.setContent(related);
        } else if (this.text != null && this.html != null) {
            message.setContent(MailParts.alternative(this.text, this.html));
        } else if (this.html != null) {
            message.setContent(this.html, "text/html; charset=UTF-8");
        } else {
            message.setText(this.text == null ? "" : this.text, "UTF-8");
        }
    }

    /**
     * Remembers one attached or inline part. The part is assembled when the message is built, because these
     * methods of the interface cannot throw a {@link MessagingException}.
     *
     * @param content the bytes of the part
     * @param type its MIME type, such as {@code image/png}
     * @param name the file name of an attachment, or the content id of an inline part
     * @param partHeaders the headers to set on the part itself; empty for none
     * @param disposition {@link Part#ATTACHMENT} or {@link Part#INLINE}
     * @return this builder
     */
    private MessageBuilder part(final byte[] content, final String type, final String name,
        final Collection<Header> partHeaders, final String disposition)
    {
        this.parts.add(new MailParts.Attachment(content, type, name, partHeaders, disposition));
        return this;
    }

    /**
     * Remembers recipients of one kind.
     *
     * @param type whether these are To, Cc or Bcc recipients
     * @param addresses the addresses to add
     * @return this builder
     */
    private MessageBuilder recipients(final RecipientType type, final InternetAddress[] addresses)
    {
        final List<String> into = recipientsOf(type);
        Arrays.stream(addresses).map(InternetAddress::toString).forEach(into::add);
        return this;
    }

    private List<String> recipientsOf(final RecipientType type)
    {
        if (RecipientType.CC.equals(type)) {
            return this.cc;
        }
        return RecipientType.BCC.equals(type) ? this.bcc : this.to;
    }

    private static void setRecipients(final MimeMessage message, final RecipientType type,
        final List<String> addresses) throws MessagingException
    {
        if (!addresses.isEmpty()) {
            message.setRecipients(type, parse(addresses.toArray(new String[0])));
        }
    }

    /**
     * An address with a display name, the name encoded in UTF-8.
     *
     * @param address the email address, such as {@code sender@example.com}
     * @param name the display name, such as {@code The Sender}
     * @return the address with its name
     * @throws AddressException if the address is not a valid email address
     */
    private static InternetAddress address(final String address, final String name) throws AddressException
    {
        return address(address, name, "UTF-8");
    }

    /**
     * An address with a display name, the name encoded in the given charset. The charset is a parameter so that
     * a test can reach the encoding failure, which UTF-8 never produces.
     *
     * @param address the email address, such as {@code sender@example.com}
     * @param name the display name, such as {@code The Sender}
     * @param charset the name of the charset to encode the display name in, such as {@code UTF-8}
     * @return the address with its name
     * @throws AddressException if the address is not a valid email address, or the charset is not supported
     */
    static InternetAddress address(final String address, final String name, final String charset)
        throws AddressException
    {
        try {
            return new InternetAddress(address, name, charset);
        } catch (final UnsupportedEncodingException e) {
            throw new AddressException("The name for " + address + " cannot be encoded: " + e.getMessage());
        }
    }

    /**
     * The given part headers, or none.
     *
     * @param headers the headers the caller passed; {@code null} if it passed none
     * @return the headers; empty if the caller passed none
     */
    private static Collection<Header> headersOr(final Collection<Header> headers)
    {
        return headers == null ? List.of() : headers;
    }

    private static InternetAddress[] parse(final String[] addresses) throws AddressException
    {
        final InternetAddress[] parsed = new InternetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            parsed[i] = new InternetAddress(addresses[i]);
        }
        return parsed;
    }
}
