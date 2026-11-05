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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.metrics.api.MetricsManager;

/**
 * A mail service that files each message under {@value #CAUGHT_MAIL_PATH} instead of sending it, so that email
 * can be read back on an instance with no mail server.
 *
 * <p>
 * Its {@code enabled} setting is off by default, and while off it registers no mail service.
 * </p>
 *
 * <p>
 * While on, it registers as a {@link MailService} ranked above Sling's {@code SimpleMailService}, which keeps
 * running. A reference already bound to Sling's service moves to the catcher only if it is dynamic and greedy.
 * </p>
 *
 * <p>
 * A message is filed on the calling thread, before {@code sendMessage} returns. A failure is logged here, and the
 * returned {@link CompletableFuture} fails with it. Each message filed adds one to the {@link CaughtMailCounter}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
// No declared service: activate registers the MailService itself, and only while enabled
@Component(service = {})
@Designate(ocd = CaughtMailService.Config.class)
public class CaughtMailService implements MailService
{
    /**
     * The catcher's setting, as the Felix console shows it.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @ObjectClassDefinition(name = "IAP Email Catcher",
        description = "Files what would have been emailed into the repository instead of sending it, so that"
            + " email can be exercised without a mail server. Off unless switched on here.")
    public @interface Config
    {
        /**
         * Whether to catch mail rather than send it.
         *
         * @return {@code true} to file messages instead of sending them
         */
        @AttributeDefinition(name = "Enabled",
            description = "While this is on, everything the platform would have emailed is filed under"
                + " /CaughtMail and nothing is delivered. Never turn it on where real mail is expected to"
                + " arrive.")
        boolean enabled() default false;
    }

    /** Where caught messages are filed. */
    public static final String CAUGHT_MAIL_PATH = "/CaughtMail";

    /**
     * A service property, set to {@code true} on the catcher's mail service registration. A target filter on it
     * finds the catcher while it is on, and nothing while it is off.
     */
    public static final String CATCHER_PROPERTY = "iap.mail.catcher";

    /** The node type of one caught message. */
    static final String MESSAGE_TYPE = "mail:CaughtMessage";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaughtMailService.class);

    private static final Map<String, Object> SERVICE_USER = Map.of(ResourceResolverFactory.SUBSERVICE, "emailcatcher");

    /** The property that sets the type of a node created through Sling. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /**
     * The headers stored as properties of their own, and so left out of {@code headers}. {@code Date} stays in
     * {@code headers}: {@code caughtAt} records when the message was caught, not when it was built.
     */
    private static final List<String> OWN_PROPERTIES = List.of("From", "To", "Cc", "Bcc", "Reply-To", "Subject");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private MetricsManager metricsManager;

    /** The mail service registration; {@code null} while the catcher is off. */
    private ServiceRegistration<MailService> registration;

    /** The counter of caught messages; {@code null} while the catcher is off. */
    private CaughtMailCounter caught;

    /**
     * Registers the catcher as a mail service, if it is switched on.
     *
     * @param bundleContext the context of this bundle, to register the mail service in
     * @param config the catcher's setting
     */
    @Activate
    void activate(final BundleContext bundleContext, final Config config)
    {
        if (!config.enabled()) {
            LOGGER.debug("The email catcher is switched off, so mail will be sent normally");
            return;
        }
        // Sling's SimpleMailService is configured without a service.ranking, so it ranks at zero
        final Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, 1000);
        properties.put(CATCHER_PROPERTY, Boolean.TRUE);
        this.registration = bundleContext.registerService(MailService.class, this, properties);
        this.caught = CaughtMailCounter.define(this.metricsManager, CAUGHT_MAIL_PATH);
        LOGGER.info("The email catcher is on: mail will be filed under {} instead of being sent",
            CAUGHT_MAIL_PATH);
    }

    /** Withdraws the catcher's mail service, if it registered one. */
    @Deactivate
    void deactivate()
    {
        if (this.registration != null) {
            this.registration.unregister();
            this.registration = null;
        }
        this.caught = null;
    }

    @Override
    public MessageBuilder getMessageBuilder()
    {
        return new CaughtMessageBuilder();
    }

    @Override
    public CompletableFuture<Void> sendMessage(final MimeMessage message)
    {
        try {
            file(message);
            return CompletableFuture.completedFuture(null);
        } catch (final MessagingException | LoginException | PersistenceException | RuntimeException e) {
            // Logged as well as returned, since a caller may drop the future
            LOGGER.error("A message could not be caught: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Files one message as a new {@value #MESSAGE_TYPE} node under {@value #CAUGHT_MAIL_PATH}.
     *
     * @param message the message to file
     * @throws MessagingException if the message cannot be read
     * @throws LoginException if the catcher's service user is not available
     * @throws PersistenceException if {@value #CAUGHT_MAIL_PATH} does not exist, or the node cannot be saved
     */
    private void file(final MimeMessage message)
        throws MessagingException, LoginException, PersistenceException
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            final Resource home = resolver.getResource(CAUGHT_MAIL_PATH);
            if (home == null) {
                throw new PersistenceException(CAUGHT_MAIL_PATH + " does not exist, so no message can be caught");
            }
            resolver.create(home, UUID.randomUUID().toString(), describe(message));
            resolver.commit();
            // After the commit, so that a message that failed to save is not counted
            if (this.caught != null) {
                this.caught.count();
            }
            LOGGER.info("Caught a message to {} at {}", String.join(", ", addresses(message, RecipientType.TO)),
                CAUGHT_MAIL_PATH);
        }
    }

    /**
     * The properties of the node that files a message. A header or body the message lacks gets no property.
     *
     * @param message the message to describe
     * @return each property's name, mapped to its value
     * @throws MessagingException if the message cannot be read
     */
    private static Map<String, Object> describe(final MimeMessage message) throws MessagingException
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE, MESSAGE_TYPE);
        // Not the Date header, which has second precision. Readers sort by this property.
        properties.put("caughtAt", Calendar.getInstance());
        put(properties, "subject", message.getSubject());
        putAll(properties, "from", addresses(message.getFrom()));
        putAll(properties, "replyTo", addresses(message.getReplyTo()));
        putAll(properties, "to", addresses(message, RecipientType.TO));
        putAll(properties, "cc", addresses(message, RecipientType.CC));
        putAll(properties, "bcc", addresses(message, RecipientType.BCC));
        putAll(properties, "headers", otherHeaders(message));
        final Bodies bodies = new Bodies();
        bodies.read(message);
        put(properties, "textBody", bodies.text);
        put(properties, "htmlBody", bodies.html);
        return properties;
    }

    /**
     * The message's headers, except those stored as properties of their own.
     *
     * @param message the message being filed
     * @return each header as {@code Name: value}; empty if there are no others
     * @throws MessagingException if the headers cannot be read
     */
    private static List<String> otherHeaders(final MimeMessage message) throws MessagingException
    {
        final List<String> remaining = new ArrayList<>();
        for (final Header header : Collections.list(message.getAllHeaders())) {
            if (!OWN_PROPERTIES.contains(header.getName())) {
                remaining.add(header.getName() + ": " + header.getValue());
            }
        }
        return remaining;
    }

    private static List<String> addresses(final MimeMessage message, final RecipientType type)
        throws MessagingException
    {
        return addresses(message.getRecipients(type));
    }

    /**
     * Addresses as they were written on the message, display names included, such as
     * {@code The Sender <sender@example.com>}.
     *
     * @param addresses the addresses to read; {@code null} if the message has none of this kind
     * @return the addresses; empty if there are none
     */
    private static List<String> addresses(final Address[] addresses)
    {
        return addresses == null ? List.of()
            : Arrays.stream(addresses).map(Address::toString).toList();
    }

    private static void put(final Map<String, Object> properties, final String name, final String value)
    {
        if (value != null && !value.isEmpty()) {
            properties.put(name, value);
        }
    }

    private static void putAll(final Map<String, Object> properties, final String name, final List<String> values)
    {
        if (!values.isEmpty()) {
            properties.put(name, values.toArray(new String[0]));
        }
    }

    /**
     * The plain text and HTML bodies of a message. It reads every part of every multipart, however nested, and
     * skips the parts whose disposition is {@code attachment}. Attachments are not filed.
     *
     * @since 0.1.0
     */
    static final class Bodies
    {
        private String text;

        private String html;

        /**
         * Reads the bodies a part holds, descending into multiparts. A later body of the same type replaces an
         * earlier one.
         *
         * @param part the message, or one part of it
         * @throws MessagingException if the part cannot be read
         */
        void read(final Part part) throws MessagingException
        {
            try {
                final Object content = part.getContent();
                if (content instanceof Multipart) {
                    final Multipart multipart = (Multipart) content;
                    for (int i = 0; i < multipart.getCount(); i++) {
                        read(multipart.getBodyPart(i));
                    }
                    return;
                }
                if (!(content instanceof String) || Part.ATTACHMENT.equals(part.getDisposition())) {
                    return;
                }
                if (part.isMimeType("text/html")) {
                    this.html = (String) content;
                } else if (part.isMimeType("text/plain")) {
                    this.text = (String) content;
                }
            } catch (final IOException e) {
                throw new MessagingException("The body of a message could not be read", e);
            }
        }
    }
}
