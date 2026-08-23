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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sender, subject and body of an email the platform sends, kept in the repository so that a deployment can reword
 * it without touching code. A template is turned into an actual {@link Email} by
 * {@link #getEmailBuilder(Map) filling in its variables} and setting a recipient.
 *
 * <p>
 * Both body parts may contain <code>${variable}</code> placeholders. The values come from the template's own extra
 * properties, overridden by whatever the caller passes in, and the subject line is substituted the same way.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class EmailTemplate
{
    /** The node type of a stored email template. */
    public static final String NODETYPE = "mail:Template";

    /** The path holding the header, footer and attachments shared by every template. */
    public static final String COMMON_TEMPLATES_PATH = "/libs/iap/mailTemplates/";

    /** The name of the property holding the sender address. */
    public static final String SENDER_ADDRESS_PROPERTY = "senderAddress";

    /** The name of the property holding the sender display name. */
    public static final String SENDER_NAME_PROPERTY = "senderName";

    /** The name of the property holding the reply-to address. */
    public static final String REPLY_TO_ADDRESS_PROPERTY = "replyToAddress";

    /** The name of the property holding the reply-to display name. */
    public static final String REPLY_TO_NAME_PROPERTY = "replyToName";

    /** The name of the property holding the subject line. */
    public static final String SUBJECT_PROPERTY = "subject";

    /** The name of the child file holding the HTML body template. */
    public static final String HTML_TEMPLATE_NODE = "bodyTemplate.html";

    /** The name of the child file holding the plain text body template. */
    public static final String TEXT_TEMPLATE_NODE = "bodyTemplate.txt";

    /** The name of the child file holding the HTML header prepended to the body. */
    public static final String HTML_BODY_HEADER = "bodyTemplate.header.html";

    /** The name of the child file holding the HTML footer appended to the body. */
    public static final String HTML_BODY_FOOTER = "bodyTemplate.footer.html";

    /** The name of the child file holding the plain text header prepended to the body. */
    public static final String TEXT_BODY_HEADER = "bodyTemplate.header.txt";

    /** The name of the child file holding the plain text footer appended to the body. */
    public static final String TEXT_BODY_FOOTER = "bodyTemplate.footer.txt";

    /** The prefix of the property asking for one of the common attachments to be included. */
    public static final String INCLUDE_ATTACHMENT_PREFIX = "includeAttachment_";

    private String senderAddress;

    private String senderName;

    private String replyToAddress;

    private String replyToName;

    private String subject;

    private String htmlTemplate;

    private String textTemplate;

    private final Map<String, String> properties = new HashMap<>();

    private final List<InlineAttachment> inlineAttachments = new LinkedList<>();

    /** Only built through a {@link #builder() builder}, or copied into an {@link Email}. */
    protected EmailTemplate()
    {
        // Built by the builder
    }

    /**
     * Copy constructor, used when an {@link Email} is instantiated from a template.
     *
     * @param other the template to copy
     */
    protected EmailTemplate(@NotNull final EmailTemplate other)
    {
        this.senderAddress = other.senderAddress;
        this.senderName = other.senderName;
        this.replyToAddress = other.replyToAddress;
        this.replyToName = other.replyToName;
        this.subject = other.subject;
        this.htmlTemplate = other.htmlTemplate;
        this.textTemplate = other.textTemplate;
        this.inlineAttachments.addAll(other.inlineAttachments);
        this.properties.putAll(other.properties);
    }

    /**
     * The email address the email is sent from, part of the {@code From} header together with
     * {@link #getSenderName()}.
     *
     * @return an email address
     */
    @NotNull
    public String getSenderAddress()
    {
        return this.senderAddress;
    }

    /**
     * The name displayed as the sender, part of the {@code From} header together with {@link #getSenderAddress()}.
     *
     * @return a name, may be {@code null}
     */
    @Nullable
    public String getSenderName()
    {
        return this.senderName;
    }

    /**
     * The email address to send replies to, defaulting to the sender address.
     *
     * @return an email address
     */
    @NotNull
    public String getReplyToAddress()
    {
        return StringUtils.defaultIfBlank(this.replyToAddress, getSenderAddress());
    }

    /**
     * The name displayed as the reply-to destination, defaulting to the sender name.
     *
     * @return a name, may be {@code null}
     */
    @Nullable
    public String getReplyToName()
    {
        return StringUtils.defaultIfBlank(this.replyToName, getSenderName());
    }

    /**
     * The subject line, which may itself contain <code>${variable}</code> placeholders.
     *
     * @return a subject, before any substitution
     */
    @NotNull
    public String getSubject()
    {
        return this.subject;
    }

    /**
     * The variables available to the body templates and the subject, as configured on the template itself. Whatever
     * a caller passes to {@link #getEmailBuilder(Map)} takes precedence over these.
     *
     * @return a map from variable name to value
     */
    @NotNull
    public Map<String, String> getExtraProperties()
    {
        return new HashMap<>(this.properties);
    }

    /**
     * The files sent along with the email and displayed inside it.
     *
     * @return the attachments, an empty list if there are none
     */
    @NotNull
    public List<InlineAttachment> getInlineAttachments()
    {
        return new ArrayList<>(this.inlineAttachments);
    }

    /**
     * The template for the HTML part of the body, header and footer included.
     *
     * @return a large string, may be {@code null} if the email has no HTML part
     */
    @Nullable
    public String getHtmlTemplate()
    {
        return this.htmlTemplate;
    }

    /**
     * The template for the plain text part of the body, header and footer included.
     *
     * @return a large string, may be {@code null} if the email has no plain text part
     */
    @Nullable
    public String getTextTemplate()
    {
        return this.textTemplate;
    }

    /**
     * Start instantiating this template into an actual email, with the body parts and the subject already filled in.
     * A recipient still has to be {@link Email.Builder#withRecipient set} before the email can be built.
     *
     * @param variables the values to substitute, taking precedence over the template's own extra properties, may be
     *            {@code null} when the template's own properties are all there is
     * @return an email builder based on this template
     */
    @NotNull
    public Email.Builder getEmailBuilder(@Nullable final Map<String, String> variables)
    {
        final Map<String, String> values = getExtraProperties();
        if (variables != null) {
            values.putAll(variables);
        }
        final Email.Builder builder = new Email.Builder(this);
        builder.withSubject(EmailUtils.render(this.subject, values));
        return builder.withBody(EmailUtils.render(this.htmlTemplate, values),
            EmailUtils.render(this.textTemplate, values));
    }

    /**
     * Start instantiating this template into an actual email, leaving the body parts to the caller.
     *
     * @return an email builder based on this template, with no body set
     */
    @NotNull
    public Email.Builder getEmailBuilder()
    {
        return new Email.Builder(this);
    }

    /**
     * Start building a template from scratch.
     *
     * @return a new template builder
     */
    @NotNull
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Start building a template from a stored one. If the stored template is fully configured it can be
     * {@link Builder#build() built} straight away, but more properties or attachments may be added first.
     *
     * @param template a JCR node of type {@value #NODETYPE}
     * @param resolver a resource resolver, needed for determining the media type of the attachments
     * @return a template builder already set up with the stored data
     * @throws RepositoryException if reading the repository fails
     * @throws IOException if reading the body templates or the attachments fails
     */
    @NotNull
    public static Builder builder(@NotNull final Node template, @NotNull final ResourceResolver resolver)
        throws RepositoryException, IOException
    {
        return new Builder(template, resolver);
    }

    /**
     * A builder for {@link EmailTemplate}, obtained from {@link EmailTemplate#builder()} or
     * {@link EmailTemplate#builder(Node, ResourceResolver)}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final EmailTemplate instance;

        private Builder()
        {
            this.instance = new EmailTemplate();
        }

        private Builder(final Node template, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            this();
            readProperties(template);
            readCommonAttachments(resolver);
            readBodiesAndAttachments(template, resolver);
        }

        /**
         * Set the HTML body template. Optional; a template may have only a plain text body.
         *
         * @param body a large string, {@code null} to leave the email without an HTML part
         * @return this builder
         */
        @NotNull
        public Builder withHtmlTemplate(@Nullable final String body)
        {
            this.instance.htmlTemplate = body;
            return this;
        }

        /**
         * Set the plain text body template. Optional; a template may have only an HTML body.
         *
         * @param body a large string, {@code null} to leave the email without a plain text part
         * @return this builder
         */
        @NotNull
        public Builder withTextTemplate(@Nullable final String body)
        {
            this.instance.textTemplate = body;
            return this;
        }

        /**
         * Set the sender address. Mandatory.
         *
         * @param address a valid email address
         * @return this builder
         */
        @NotNull
        public Builder withSenderAddress(@NotNull final String address)
        {
            this.instance.senderAddress = address;
            return this;
        }

        /**
         * Set the sender display name. Optional.
         *
         * @param name a display name, {@code null} to send from the address alone
         * @return this builder
         */
        @NotNull
        public Builder withSenderName(@Nullable final String name)
        {
            this.instance.senderName = name;
            return this;
        }

        /**
         * Set the address replies should go to. Optional, defaults to the sender address.
         *
         * @param address a valid email address, {@code null} to reply to the sender
         * @return this builder
         */
        @NotNull
        public Builder withReplyToAddress(@Nullable final String address)
        {
            this.instance.replyToAddress = address;
            return this;
        }

        /**
         * Set the display name for the reply-to address. Optional, defaults to the sender name.
         *
         * @param name a display name, {@code null} to reuse the sender name
         * @return this builder
         */
        @NotNull
        public Builder withReplyToName(@Nullable final String name)
        {
            this.instance.replyToName = name;
            return this;
        }

        /**
         * Set the subject line. Mandatory. It may contain <code>${variable}</code> placeholders.
         *
         * @param subject a short string
         * @return this builder
         */
        @NotNull
        public Builder withSubject(@NotNull final String subject)
        {
            this.instance.subject = subject;
            return this;
        }

        /**
         * Add a variable available to the subject and the body templates.
         *
         * @param name the name to use in a <code>${...}</code> placeholder
         * @param value the value to substitute
         * @return this builder
         */
        @NotNull
        public Builder withProperty(@NotNull final String name, @NotNull final String value)
        {
            this.instance.properties.put(name, value);
            return this;
        }

        /**
         * Add a file to send along with the email and display inside it. An attachment already added under the same
         * name is replaced, which is how a template overrides one of the common attachments.
         *
         * @param name the name of the attachment, and the content ID the body references it by
         * @param mimeType the media type of the content
         * @param content the content itself, {@code null} for an empty attachment
         * @return this builder
         */
        @NotNull
        public Builder withInlineAttachment(@NotNull final String name, @NotNull final String mimeType,
            @Nullable final byte[] content)
        {
            this.instance.inlineAttachments.removeIf(attachment -> attachment.getName().equals(name));
            this.instance.inlineAttachments.add(new InlineAttachment(name, mimeType, content));
            return this;
        }

        /**
         * Retrieve the built template. The builder should be discarded afterwards.
         *
         * @return an {@link EmailTemplate}
         * @throws IllegalStateException if the sender address or the subject are missing
         */
        @NotNull
        public EmailTemplate build()
        {
            if (this.instance.senderAddress == null) {
                throw new IllegalStateException("The email template has no sender address");
            }
            if (this.instance.subject == null) {
                throw new IllegalStateException("The email template has no subject");
            }
            return this.instance;
        }

        private void readProperties(final Node template) throws RepositoryException
        {
            final PropertyIterator properties = template.getProperties();
            while (properties.hasNext()) {
                final Property property = properties.nextProperty();
                final String name = property.getName();
                if (name.startsWith("jcr:") || name.startsWith("sling:") || property.isMultiple()) {
                    continue;
                }
                final String value = property.getString();
                switch (name) {
                    case SENDER_ADDRESS_PROPERTY -> withSenderAddress(value);
                    case SENDER_NAME_PROPERTY -> withSenderName(value);
                    case REPLY_TO_ADDRESS_PROPERTY -> withReplyToAddress(value);
                    case REPLY_TO_NAME_PROPERTY -> withReplyToName(value);
                    case SUBJECT_PROPERTY -> withSubject(value);
                    default -> withProperty(name, value);
                }
            }
        }

        /**
         * Includes the shared attachments the template asked for, by setting an
         * {@value EmailTemplate#INCLUDE_ATTACHMENT_PREFIX} property naming each of them.
         *
         * @param resolver a resource resolver
         * @throws RepositoryException if reading the repository fails
         * @throws IOException if reading an attachment fails
         */
        private void readCommonAttachments(final ResourceResolver resolver) throws RepositoryException, IOException
        {
            final Resource common = resolver.getResource(COMMON_TEMPLATES_PATH + "commonAttachments");
            final Node commonNode = common == null ? null : common.adaptTo(Node.class);
            if (commonNode == null) {
                return;
            }
            final NodeIterator children = commonNode.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                if (child.isNodeType("nt:file")
                    && this.instance.properties.containsKey(INCLUDE_ATTACHMENT_PREFIX + child.getName())) {
                    withInlineAttachment(child.getName(), getMimeType(child, resolver), readBytes(child));
                }
            }
        }

        private void readBodiesAndAttachments(final Node template, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            final Session session = template.getSession();
            final String[] html = { readCommon(session, HTML_BODY_HEADER), null,
                readCommon(session, HTML_BODY_FOOTER) };
            final String[] text = { readCommon(session, TEXT_BODY_HEADER), null,
                readCommon(session, TEXT_BODY_FOOTER) };
            final NodeIterator children = template.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                switch (child.getName()) {
                    case HTML_BODY_HEADER -> html[0] = readString(child);
                    case HTML_TEMPLATE_NODE -> html[1] = readString(child);
                    case HTML_BODY_FOOTER -> html[2] = readString(child);
                    case TEXT_BODY_HEADER -> text[0] = readString(child);
                    case TEXT_TEMPLATE_NODE -> text[1] = readString(child);
                    case TEXT_BODY_FOOTER -> text[2] = readString(child);
                    default -> addIfFile(child, resolver);
                }
            }
            withHtmlTemplate(wrapBody(html));
            withTextTemplate(wrapBody(text));
        }

        /**
         * Wraps a stored body in its header and footer, or reports that the template has no such part at all. A
         * header and a footer are wrapped <em>around</em> a body, so on their own they do not make one: a template
         * carrying no {@code bodyTemplate} of a kind has no part of that kind, which is what lets
         * {@link EmailUtils#sendTextEmail} refuse to send an HTML-only template as a plain text email with nothing
         * in it but the shared decoration.
         *
         * @param parts the header, the body and the footer, in that order, the body {@code null} if the template
         *            does not carry one
         * @return the three joined together, or {@code null} if there is no body to wrap
         */
        private static String wrapBody(final String[] parts)
        {
            return parts[1] == null ? null : String.join("", parts);
        }

        private void addIfFile(final Node child, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            if (child.isNodeType("nt:file")) {
                withInlineAttachment(child.getName(), getMimeType(child, resolver), readBytes(child));
            }
        }

        private String readCommon(final Session session, final String name) throws RepositoryException, IOException
        {
            final String path = COMMON_TEMPLATES_PATH + name;
            return session.nodeExists(path) ? readString(session.getNode(path)) : "";
        }

        private String readString(final Node file) throws RepositoryException, IOException
        {
            final Binary content = getContent(file);
            try (InputStream stream = content.getStream()) {
                return IOUtils.toString(stream, StandardCharsets.UTF_8);
            } finally {
                content.dispose();
            }
        }

        private byte[] readBytes(final Node file) throws RepositoryException, IOException
        {
            final Binary content = getContent(file);
            try (InputStream stream = content.getStream()) {
                return stream.readAllBytes();
            } finally {
                content.dispose();
            }
        }

        /**
         * The binary content of a file node. The caller must dispose of it, and close the stream it takes from it:
         * a repository binary holds resources until it is told it is no longer needed.
         *
         * @param file an {@code nt:file} node
         * @return its content
         * @throws RepositoryException if the node is not a readable file
         */
        private Binary getContent(final Node file) throws RepositoryException
        {
            return file.getNode("jcr:content").getProperty("jcr:data").getBinary();
        }

        private String getMimeType(final Node file, final ResourceResolver resolver) throws RepositoryException
        {
            return Optional.ofNullable(resolver.getResource(file.getPath()))
                .map(resource -> resource.getResourceMetadata().getContentType())
                .filter(StringUtils::isNotBlank)
                .orElse("application/octet-stream");
        }
    }
}
