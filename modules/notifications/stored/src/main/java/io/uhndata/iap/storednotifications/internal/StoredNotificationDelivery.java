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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.commons.text.StringSubstitutor;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.util.Text;
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
 * Keeps each notification for its recipient to read in the application. It stores a {@code notif:Notification} in
 * the recipient's folder, {@code /Notifications/<user id>}, which only they are granted read on.
 *
 * <p>
 * It accepts a notification of any urgency if it has a message to store. It declines one whose template folder has
 * no {@code uiMessage} and whose subject has no title.
 * </p>
 *
 * <p>
 * It writes as its own service user, and commits before it returns. A failure is logged and recorded, and the
 * delivery is declined.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationDelivery.class)
public class StoredNotificationDelivery implements NotificationDelivery
{
    /** The subservice name under which this bundle's service user is mapped. */
    static final String SUBSERVICE_NAME = "storednotifications";

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredNotificationDelivery.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public boolean deliver(final NotificationContext notification, final Recipient recipient)
    {
        final String message = renderMessage(notification);
        if (message == null || message.isBlank()) {
            LOGGER.debug("The {} notification has no message, skipping", notification.getEvent());
            return false;
        }
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            store(resolver, notification, recipient, message);
            resolver.commit();
            LOGGER.debug("Stored the {} notification about {}", notification.getEvent(),
                notification.getSubject().getPath());
            return true;
        } catch (final LoginException | PersistenceException | RepositoryException | RuntimeException e) {
            LOGGER.error("The {} notification could not be stored: {}", notification.getEvent(),
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(StoredNotificationDelivery.class, "deliver")
                .about(notification.getSubject().getPath())
                .with("event", notification.getEvent())
                .with("recipient", recipient.userId()));
            return false;
        }
    }

    /**
     * Writes one notification into a prefix-tree bucket of its recipient's folder.
     *
     * @param resolver this delivery's own session
     * @param notification what happened
     * @param recipient the person it is for
     * @param message the rendered message
     * @return the created notification
     * @throws RepositoryException if the folder or the bucket cannot be created, or the folder cannot be granted
     * @throws PersistenceException if the notification cannot be written
     */
    private static Resource store(final ResourceResolver resolver, final NotificationContext notification,
        final Recipient recipient, final String message) throws RepositoryException, PersistenceException
    {
        final Node home = recipientHome(resolver, recipient.userId());
        final String name = UUID.randomUUID().toString().replace("-", "");
        final Node bucket = PrefixTree.bucketFor(home, name, "sling:Folder");
        final Resource parent = Objects.requireNonNull(resolver.getResource(bucket.getPath()),
            "A bucket this session just created is visible to it");
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "notif:Notification");
        properties.put(StoredNotifications.RECIPIENT_PROPERTY, recipient.userId());
        properties.put(StoredNotifications.MESSAGE_PROPERTY, message);
        properties.put("event", notification.getEvent());
        properties.put("subject", notification.getSubject().getPath());
        properties.put("urgency", notification.getUrgency());
        if (notification.getActor() != null) {
            properties.put("actor", notification.getActor());
        }
        return resolver.create(parent, name, properties);
    }

    /**
     * The folder holding one recipient's notifications, created and granted to them on first use.
     *
     * @param resolver this delivery's own session
     * @param userId the recipient's user id
     * @return the folder node
     * @throws RepositoryException if the folder cannot be created or granted
     */
    private static Node recipientHome(final ResourceResolver resolver, final String userId)
        throws RepositoryException
    {
        final Resource root = Objects.requireNonNull(resolver.getResource(StoredNotifications.HOMEPAGE_PATH),
            "The stored notifications homepage is created by repoinit before this bundle can run");
        final Node homepage = Objects.requireNonNull(root.adaptTo(Node.class),
            "A repoinit-created resource is backed by a node");
        final String name = Text.escapeIllegalJcrChars(userId);
        if (homepage.hasNode(name)) {
            return homepage.getNode(name);
        }
        final Node home = homepage.addNode(name, "sling:Folder");
        grantRead(resolver, home.getPath(), userId);
        return home;
    }

    /**
     * Lets the recipient read, and mark as read, everything in their folder. A grant that fails fails the delivery,
     * since a notification its recipient cannot read is lost.
     *
     * @param resolver this delivery's own session
     * @param path the path of the recipient's folder
     * @param userId the recipient's user id
     * @throws RepositoryException if the session cannot manage access control, the recipient has no account, or the
     *             entry cannot be written
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
        // Read to see them, and modifyProperties to mark them read. Everything in the folder inherits it.
        acl.addAccessControlEntry(account.getPrincipal(), new Privilege[] {
            manager.privilegeFromName(Privilege.JCR_READ),
            manager.privilegeFromName(Privilege.JCR_MODIFY_PROPERTIES) });
        manager.setPolicy(path, acl);
    }

    /**
     * The access control list of a node: the one it has, or else a new one that can be put on it.
     *
     * @param manager the repository's access control manager
     * @param path the path of the node
     * @return a list to add entries to, then set on the node
     * @throws RepositoryException if the node has no list and none can be put on it
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
        throw new RepositoryException("The access control list cannot be put on " + path);
    }

    /**
     * Renders the message to store. It is the template folder's {@code uiMessage} with its placeholders filled in,
     * or else the subject's title and the event name, such as {@code A long weekend: approved}.
     *
     * @param notification what happened
     * @return the message, or {@code null} if there is no {@code uiMessage} and the subject has no title
     */
    private static String renderMessage(final NotificationContext notification)
    {
        final Map<String, String> variables = variables(notification);
        final String template = messageTemplate(notification);
        if (template != null) {
            final StringSubstitutor substitutor = new StringSubstitutor(variables);
            // A value is often text somebody typed, so a ${...} inside it is kept as typed.
            // A placeholder with no value is kept as written.
            substitutor.setDisableSubstitutionInValues(true);
            return substitutor.replace(template);
        }
        // variables() always sets subjectTitle, to an empty string if the subject has no title
        final String title = variables.get("subjectTitle");
        return title.isBlank() ? null : title + ": " + notification.getEvent();
    }

    /**
     * The {@code uiMessage} of the notification's template folder, read through the subject's session.
     *
     * @param notification what happened
     * @return the message with its placeholders, or {@code null} if the notification names no template, the folder
     *         is missing, or it has no {@code uiMessage}
     */
    private static String messageTemplate(final NotificationContext notification)
    {
        final String template = notification.getTemplate();
        if (template == null) {
            return null;
        }
        final Resource folder = notification.getSubject().getResourceResolver().getResource(template);
        return folder == null ? null : folder.getValueMap().get(StoredNotifications.UI_MESSAGE_PROPERTY, String.class);
    }

    /**
     * The values a message can use: the notification's own variables, and {@code subjectPath}, {@code subjectTitle}
     * and {@code event}. These three replace a notification variable of the same name.
     *
     * @param notification what happened
     * @return the values as strings, by variable name
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
