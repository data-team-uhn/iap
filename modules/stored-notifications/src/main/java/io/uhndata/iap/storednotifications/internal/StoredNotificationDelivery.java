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
package io.uhndata.iap.storednotifications.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;
import io.uhndata.iap.storednotifications.api.StoredNotifications;
import io.uhndata.iap.utils.PrefixTree;

/**
 * The delivery that keeps notifications: each one becomes a {@code notif:Notification} under
 * {@code /Notifications}, readable by its one recipient, for the interface to show.
 *
 * <p>
 * It accepts every urgency, because storing is not interrupting: an {@code immediate} decision and a
 * {@code batched} aside both belong in the list of what happened, and how loudly each was announced was the other
 * channels' business. What it declines is a notification it cannot word — no template line and a subject with no
 * title leaves nothing worth listing.
 * </p>
 *
 * <p>
 * The write happens on the delivery's own session, committed before this method returns. That is safe here —
 * deliveries run in plain service code, never inside a commit hook — and it means a notification can exist for a
 * workflow whose own commit fails a moment later, which is the same window the email channel already accepts: a
 * notification is an attempt to inform, made at the moment the workflow said to make it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationDelivery.class)
public class StoredNotificationDelivery implements NotificationDelivery
{
    /** The subservice name under which this bundle's service user is mapped. */
    static final String SUBSERVICE = "storednotifications";

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredNotificationDelivery.class);

    /** A {@code ${name}} placeholder in a template line. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public boolean deliver(final NotificationContext notification, final Recipient recipient)
    {
        final String line = lineOf(notification);
        if (line == null || line.isBlank()) {
            LOGGER.info("The {} notification has no line and its subject no title, so there is nothing to list",
                notification.getEvent());
            return false;
        }
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource stored = store(resolver, notification, recipient, line);
            grantRead(resolver, stored.getPath(), recipient.userId());
            resolver.commit();
            LOGGER.info("Stored the {} notification about {} for {}", notification.getEvent(),
                notification.getSubject().getPath(), recipient.userId());
            return true;
        } catch (final LoginException | PersistenceException | RepositoryException | RuntimeException e) {
            LOGGER.error("The {} notification could not be stored for {}: {}", notification.getEvent(),
                recipient.userId(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StoredNotificationDelivery.class, "deliver")
                .about(notification.getSubject().getPath()));
            return false;
        }
    }

    /**
     * Writes one notification into its prefix-tree bucket.
     *
     * @param resolver this delivery's own session
     * @param notification what happened
     * @param recipient who it is for
     * @param line the rendered sentence a list will show
     * @return the created resource
     * @throws RepositoryException when the bucket cannot be reached
     * @throws PersistenceException when the notification cannot be written
     */
    private static Resource store(final ResourceResolver resolver, final NotificationContext notification,
        final Recipient recipient, final String line) throws RepositoryException, PersistenceException
    {
        final Resource root = Objects.requireNonNull(resolver.getResource(StoredNotifications.PATH),
            "The stored notifications homepage is created by repoinit before this bundle can run");
        // Filed by a fresh uniformly-distributed name, which is what keeps every bucket small forever
        final String name = UUID.randomUUID().toString().replace("-", "");
        final Node bucket = PrefixTree.bucketFor(
            Objects.requireNonNull(root.adaptTo(Node.class), "A repoinit-created resource is backed by a node"),
            name, "sling:Folder");
        final Resource parent = Objects.requireNonNull(resolver.getResource(bucket.getPath()),
            "A bucket this session just created is visible to it");
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "notif:Notification");
        properties.put(StoredNotifications.RECIPIENT, recipient.userId());
        properties.put(StoredNotifications.LINE, line);
        properties.put("event", notification.getEvent());
        properties.put("subject", notification.getSubject().getPath());
        properties.put("urgency", notification.getUrgency());
        if (notification.getActor() != null) {
            properties.put("actor", notification.getActor());
        }
        return resolver.create(parent, name, properties);
    }

    /**
     * Lets the one recipient read, and mark as read, what was stored for them. Everything else about the node
     * stays invisible to everybody, which is what makes a listing on the reader's own session already-filtered.
     *
     * @param resolver this delivery's own session
     * @param path the stored notification
     * @param userId who may read it
     * @throws RepositoryException when the entry cannot be written, which fails the whole delivery: a
     *             notification its recipient cannot see is not delivered, it is lost
     */
    private static void grantRead(final ResourceResolver resolver, final String path, final String userId)
        throws RepositoryException
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession jackrabbit)) {
            throw new RepositoryException("The session cannot manage access control");
        }
        final Authorizable account = jackrabbit.getUserManager().getAuthorizable(userId);
        if (account == null) {
            throw new RepositoryException("No account to grant to: " + userId);
        }
        final AccessControlManager manager = session.getAccessControlManager();
        final AccessControlList acl = listFor(manager, path);
        // Read to see it, modifyProperties to flip its read marker: the node holds nothing about anybody else,
        // so the worst the recipient can do with the grant is rewrite what they alone can see
        acl.addAccessControlEntry(account.getPrincipal(), new Privilege[] {
            manager.privilegeFromName(Privilege.JCR_READ),
            manager.privilegeFromName(Privilege.JCR_MODIFY_PROPERTIES) });
        manager.setPolicy(path, acl);
    }

    /**
     * The resource's own access control list, whether it already has one or is getting its first.
     *
     * @param manager the repository's access control manager
     * @param path the resource
     * @return a modifiable list
     * @throws RepositoryException when the repository will not hand one over
     */
    private static AccessControlList listFor(final AccessControlManager manager, final String path)
        throws RepositoryException
    {
        for (final AccessControlPolicy policy : manager.getPolicies(path)) {
            if (policy instanceof AccessControlList list) {
                return list;
            }
        }
        for (final AccessControlPolicyIterator candidates = manager.getApplicablePolicies(path);
            candidates.hasNext();) {
            final AccessControlPolicy policy = candidates.nextAccessControlPolicy();
            if (policy instanceof AccessControlList list) {
                return list;
            }
        }
        throw new RepositoryException("No access control list can be put on " + path);
    }

    /**
     * The sentence a list will show for this notification: the template's {@code line} with its placeholders
     * filled in, or a plain statement of title and event when the wording folder does not carry one.
     *
     * @param notification what happened
     * @return the rendered line, or {@code null} when there is nothing to say
     */
    private static String lineOf(final NotificationContext notification)
    {
        final Map<String, String> variables = variables(notification);
        final String template = lineTemplate(notification);
        if (template != null) {
            final Matcher placeholders = PLACEHOLDER.matcher(template);
            // Unknown placeholders stay as written, so a typo is visible in the list instead of vanishing
            return placeholders.replaceAll(match -> Matcher.quoteReplacement(
                variables.getOrDefault(match.group(1), match.group())));
        }
        // Always present in the map, possibly empty: variables() fills it from the subject with a default
        final String title = variables.get("subjectTitle");
        return title.isBlank() ? null : title + ": " + notification.getEvent();
    }

    /**
     * The {@code line} the notification's wording folder carries, when it names one and it does.
     *
     * @param notification what happened
     * @return the raw line template, or {@code null}
     */
    private static String lineTemplate(final NotificationContext notification)
    {
        final String template = notification.getTemplate();
        if (template == null) {
            return null;
        }
        final Resource wording = notification.getSubject().getResourceResolver().getResource(template);
        return wording == null ? null : wording.getValueMap().get(StoredNotifications.LINE, String.class);
    }

    /**
     * What a line may interpolate: whatever the notification carries, plus the few things every message can say
     * about itself — the same set the email wording gets.
     *
     * @param notification what happened
     * @return the variables, as the strings a line substitutes
     */
    private static Map<String, String> variables(final NotificationContext notification)
    {
        final Map<String, String> variables = new HashMap<>();
        notification.getVariables().forEach((name, value) -> variables.put(name, Objects.toString(value, "")));
        variables.put("subjectPath", notification.getSubject().getPath());
        variables.put("subjectTitle", notification.getSubject().getValueMap().get("title", ""));
        variables.put("event", notification.getEvent());
        return variables;
    }
}
