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

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An email ready to be sent to one recipient. To make one, read an {@link EmailTemplate}, fill in its variables with
 * {@link EmailTemplate#getEmailBuilder(Map)}, and set a recipient. {@link EmailUtils#sendEmail} sends it.
 *
 * @see EmailTemplate
 * @version $Id$
 * @since 0.1.0
 */
public class Email extends EmailTemplate
{
    private String toAddress;

    private String toName;

    private String htmlBody;

    private String textBody;

    private String renderedSubject;

    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    /**
     * Copies a template into a new email, for a {@link Builder}.
     *
     * @param template the template to copy
     */
    protected Email(@NotNull final EmailTemplate template)
    {
        super(template);
    }

    /**
     * The recipient's address, which goes in the {@code To} header with {@link #getRecipientName()}.
     *
     * @return an email address, such as {@code alice@example.org}
     */
    @NotNull
    public String getRecipientAddress()
    {
        return this.toAddress;
    }

    /**
     * The recipient's name, which goes in the {@code To} header with {@link #getRecipientAddress()}.
     *
     * @return a display name, or {@code null} if the email is addressed by the address alone
     */
    @Nullable
    public String getRecipientName()
    {
        return this.toName;
    }

    /**
     * The HTML body of the email.
     *
     * @return the HTML, or {@code null} if the email has no HTML body
     */
    @Nullable
    public String getHtmlBody()
    {
        return this.htmlBody;
    }

    /**
     * The plain text body of the email.
     *
     * @return the text, or {@code null} if the email has no plain text body
     */
    @Nullable
    public String getTextBody()
    {
        return this.textBody;
    }

    /**
     * The subject line.
     *
     * @return the subject with its variables filled in, or the template's own subject as written if the builder set
     *         none
     */
    @Override
    @NotNull
    public String getSubject()
    {
        return this.renderedSubject == null ? super.getSubject() : this.renderedSubject;
    }

    /**
     * Extra headers to set on the message, such as {@code Auto-Submitted}.
     *
     * @return a copy of the map from header name to value, in the order they were added, empty if there are none
     */
    @NotNull
    public Map<String, String> getExtraHeaders()
    {
        return new LinkedHashMap<>(this.extraHeaders);
    }

    /**
     * Builds an {@link Email}. Obtained from {@link EmailTemplate#getEmailBuilder(Map)} or
     * {@link EmailTemplate#getEmailBuilder()}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final Email instance;

        /**
         * Starts an email from a template.
         *
         * @param template the template to copy
         */
        protected Builder(@NotNull final EmailTemplate template)
        {
            this.instance = new Email(template);
        }

        /**
         * Sets the bodies of this email. {@link EmailTemplate#getEmailBuilder(Map)} has already set them. The email
         * cannot be built without at least one.
         *
         * @param htmlBody the HTML body, or {@code null} for none
         * @param textBody the plain text body, or {@code null} for none
         * @return this builder
         */
        @NotNull
        public Builder withBody(@Nullable final String htmlBody, @Nullable final String textBody)
        {
            this.instance.htmlBody = htmlBody;
            this.instance.textBody = textBody;
            return this;
        }

        /**
         * Sets the subject line of this email. {@link EmailTemplate#getEmailBuilder(Map)} has already set it.
         *
         * @param subject the subject as sent, or {@code null} to send the template's own subject as written
         * @return this builder
         */
        @NotNull
        public Builder withSubject(@Nullable final String subject)
        {
            this.instance.renderedSubject = subject;
            return this;
        }

        /**
         * Sets the recipient of this email. The email cannot be built without one.
         *
         * @param address an email address, such as {@code alice@example.org}
         * @param name a display name, or {@code null} to address the recipient by the address alone
         * @return this builder
         */
        @NotNull
        public Builder withRecipient(@NotNull final String address, @Nullable final String name)
        {
            this.instance.toAddress = address;
            this.instance.toName = name;
            return this;
        }

        /**
         * Adds a header to set on the message. It replaces an extra header of the same name.
         *
         * @param name the header name, such as {@code Auto-Submitted}
         * @param value the header value, such as {@code auto-generated}
         * @return this builder
         */
        @NotNull
        public Builder withExtraHeader(@NotNull final String name, @NotNull final String value)
        {
            this.instance.extraHeaders.put(name, value);
            return this;
        }

        /**
         * Returns the built email. The builder must not be used afterwards, since it changes the same email.
         *
         * @return the email
         * @throws IllegalStateException if the email has no recipient address, or no body at all
         */
        @NotNull
        public Email build()
        {
            if (this.instance.toAddress == null) {
                throw new IllegalStateException("The email has no recipient address");
            }
            if (this.instance.htmlBody == null && this.instance.textBody == null) {
                throw new IllegalStateException("The email has neither an HTML nor a plain text body");
            }
            return this.instance;
        }
    }
}
