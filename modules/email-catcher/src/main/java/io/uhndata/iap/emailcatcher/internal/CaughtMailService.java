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
 * A mail service that files what would have been sent, instead of sending it.
 *
 * <p>
 * <strong>This is a development facility, and what keeps it out of the way is a setting that defaults to
 * off.</strong> The bundle ships in every distribution and publishes nothing until somebody enables it, which is
 * a better safeguard than leaving the bundle out of some of them: there is one set of artifacts rather than two,
 * the difference between environments is a configuration a deployment can read back, and switching it on
 * somewhere new needs no rebuild.
 * </p>
 *
 * <p>
 * <strong>Registered above the real one rather than instead of it.</strong> Sling's {@code SimpleMailService} is
 * still there and still starts; this simply outranks it, so every {@code @Reference MailService} gets this one.
 * That is one configuration property rather than a mechanism for disabling a component that belongs to another
 * bundle, and it leaves the real service reachable by anything that deliberately asks for it.
 * </p>
 *
 * <p>
 * <strong>Filed synchronously, and loudly.</strong> {@code sendMessage} returns a {@link CompletableFuture}, and
 * the platform's callers do not consume it — a failure inside one reaches neither the caller nor the log. That is
 * survivable for a real send, which the mail server would report on separately; it is not survivable here, where
 * the whole purpose is that the message can be read back afterwards. So the write happens on the calling thread
 * and anything that goes wrong is logged as an error before the failed future is handed back.
 * </p>
 *
 * <p>
 * <strong>How much it has caught is a metric.</strong> The count answers a question the listing cannot: whether
 * anything was sent at all during a run. It rolls over nightly, so the previous day's traffic stays readable
 * beside the running total — a demo or a test environment is exercised in bursts, and a total that only ever
 * grows says nothing about whether today's run sent what it should have.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
// service = {} because the MailService registration is made by hand, in activate, only when enabled
@Component(service = {})
@Designate(ocd = CaughtMailService.Config.class)
public class CaughtMailService implements MailService
{
    /**
     * What an administrator sees in the Felix console's configuration list.
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
         * @return {@code true} while the catcher should be filing messages
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
     * Set on this service's registration, so that it can be told apart from the real mail service it outranks.
     * Whether a service carrying it exists is the only honest answer to "is mail being caught right now": the
     * configuration says what was asked for, this says what is in force.
     */
    public static final String CATCHER_PROPERTY = "iap.mail.catcher";

    /** The node type of one caught message. */
    static final String MESSAGE_TYPE = "mail:CaughtMessage";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaughtMailService.class);

    private static final Map<String, Object> SERVICE_USER = Map.of(ResourceResolverFactory.SUBSERVICE, "emailcatcher");

    /** The property naming a node's type, which has to be set explicitly when creating through Sling. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /**
     * The headers read into their own properties, and therefore left out of the catch-all list. {@code Date} is
     * not among them: it says when the message was built, which is a different fact from when it was caught, and
     * it stays readable as an ordinary header.
     */
    private static final List<String> OWN_PROPERTIES = List.of("From", "To", "Cc", "Bcc", "Reply-To", "Subject");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private MetricsManager metricsManager;

    /** The mail service registration, held only while enabled, so that deactivating can withdraw it. */
    private ServiceRegistration<MailService> registration;

    /** The counter of caught messages, held only while the catcher is on. */
    private CaughtMailCounter caught;

    /**
     * Publishes the catcher as a mail service, if it is switched on.
     *
     * @param bundleContext this bundle's context, which is what a manual registration is made against
     * @param config the setting an administrator can change
     */
    @Activate
    void activate(final BundleContext bundleContext, final Config config)
    {
        if (!config.enabled()) {
            LOGGER.debug("The email catcher is switched off, so mail will be sent normally");
            return;
        }
        // Above Sling's own mail service, which registers without a configuration and so ranks at zero. Ranking
        // decides this rather than disabling a component that belongs to another bundle, which also leaves the
        // real service reachable by anything that deliberately asks for it.
        final Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, 1000);
        // Marks this registration as the catcher's own, so that something wanting to report whether mail is
        // being caught can ask for it by target filter rather than by guessing from the ranking
        properties.put(CATCHER_PROPERTY, Boolean.TRUE);
        this.registration = bundleContext.registerService(MailService.class, this, properties);
        this.caught = CaughtMailCounter.define(this.metricsManager, CAUGHT_MAIL_PATH);
        LOGGER.info("The email catcher is on: mail will be filed under {} instead of being sent",
            CAUGHT_MAIL_PATH);
    }

    /** Withdraws the mail service, so that switching the catcher off puts real sending back. */
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
            // Said out loud, because nothing consumes the future this is reported in
            LOGGER.error("A message could not be caught: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Writes one message into the repository.
     *
     * @param message the message that would have been sent
     * @throws MessagingException if the message cannot be read
     * @throws LoginException if the catcher's service user is not available
     * @throws PersistenceException if the message cannot be written
     */
    private void file(final MimeMessage message)
        throws MessagingException, LoginException, PersistenceException
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            final Resource home = resolver.getResource(CAUGHT_MAIL_PATH);
            if (home == null) {
                throw new PersistenceException(CAUGHT_MAIL_PATH + " does not exist, so no message can be caught");
            }
            // Named after nothing in the message: a subject is not unique, not always present, and not always a
            // usable node name, while what a reader wants is ordering, which the caughtAt property carries
            resolver.create(home, UUID.randomUUID().toString(), describe(message));
            resolver.commit();
            // After the commit, so the count only ever reports messages that can actually be read back
            if (this.caught != null) {
                this.caught.count();
            }
            LOGGER.info("Caught a message to {} at {}", String.join(", ", addresses(message, RecipientType.TO)),
                CAUGHT_MAIL_PATH);
        }
    }

    /**
     * Reads a message into the properties of the node recording it.
     *
     * @param message the message to describe
     * @return the properties of a new node
     * @throws MessagingException if the message cannot be read
     */
    private static Map<String, Object> describe(final MimeMessage message) throws MessagingException
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE, MESSAGE_TYPE);
        // When it was caught, not when it was built. The message's own Date header has second precision, so
        // reading that would leave two messages sent in the same second in no particular order — and this is the
        // property a reader sorts by.
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
     * Every header except the ones read into properties of their own.
     *
     * <p>Kept so that anything the platform sets deliberately can be checked without this class having to know
     * what that might be.</p>
     *
     * @param message the message being filed
     * @return the remaining headers, as {@code Name: value}
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
     * Addresses as they were written on the message, display names and all, because what is worth checking is
     * what a recipient would have seen.
     *
     * @param addresses the addresses to read, possibly {@code null} when the message carries none
     * @return their string forms, empty when there are none
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
     * The plain text and HTML bodies of a message, whatever shape it was assembled in.
     *
     * <p>A message may be a lone body, a {@code multipart/alternative} of both, or either of those wrapped in a
     * {@code multipart/related} carrying inline parts — so this walks whatever it is given rather than assuming
     * one of them. Attachments are passed over: what they were is visible in the headers, and their bytes are not
     * what anybody reads a caught message to check.</p>
     *
     * @since 0.1.0
     */
    static final class Bodies
    {
        private String text;

        private String html;

        /**
         * Reads whatever bodies a part holds, descending into multiparts.
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
            } catch (final java.io.IOException e) {
                throw new MessagingException("The body of a message could not be read", e);
            }
        }
    }
}
