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
package io.uhndata.iap.emailnotifications.internal;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.mail.MessagingException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.messaging.mail.MailService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.emailnotifications.api.Email;
import io.uhndata.iap.emailnotifications.api.EmailTemplate;
import io.uhndata.iap.emailnotifications.api.EmailUtils;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;

/**
 * Sends a notification as an email, straight away.
 *
 * <p>
 * It accepts what it can carry and declines the rest, rather than assuming every notification is its business:
 * a recipient with no address is declined — a fact about that person's account, not an error — and so is a
 * notification with no template, since this channel has no wording of its own to fall back on. Declining is a
 * normal answer, because another delivery may well carry what this one cannot.
 * </p>
 *
 * <p>
 * It also declines anything that is not {@link NotificationContext#IMMEDIATE}. That is what makes urgency mean
 * something today rather than only in principle: a {@code batched} notification is currently delivered by
 * nothing, which is visible in the log, instead of being quietly emailed as though the workflow had never said
 * otherwise. The collector that will accept those is a second delivery, not a change here.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationDelivery.class)
public class EmailDelivery implements NotificationDelivery
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDelivery.class);

    /** The child of a notification's wording folder holding this channel's rendering. */
    private static final String EMAIL_RENDERING = "email";

    // Dynamic and greedy for the same reason the test endpoint's reference is: a deployment substituting a mail
    // service, a development instance catching mail rather than sending it, should win on ranking rather than on
    // having happened to start first
    @Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile MailService mailService;

    @Override
    public boolean deliver(final NotificationContext notification, final Recipient recipient)
    {
        if (!NotificationContext.IMMEDIATE.equals(notification.getUrgency())) {
            return false;
        }
        // Whether the platform knows how to email somebody is a fact about their account, read here because
        // this is the one delivery that needs it
        final String address = addressOf(recipient);
        if (address == null || address.isBlank()) {
            LOGGER.info("{} has no email address, so the {} notification was not emailed", recipient.userId(),
                notification.getEvent());
            return false;
        }
        final String templatePath = notification.getTemplate();
        if (templatePath == null) {
            LOGGER.warn("The {} notification names no template, so there is nothing to email",
                notification.getEvent());
            return false;
        }
        return send(notification, recipient, address, templatePath);
    }

    /**
     * Builds and hands over one message.
     *
     * @param notification what happened
     * @param recipient who to tell
     * @param address where to send it, already known to be there
     * @param templatePath where the wording lives
     * @return {@code true} if a message was handed to the mail service
     */
    private boolean send(final NotificationContext notification, final Recipient recipient, final String address,
        final String templatePath)
    {
        try {
            final Resource subject = notification.getSubject();
            final ResourceResolver resolver = subject.getResourceResolver();
            final Resource templateResource = resolver.getResource(templatePath);
            if (templateResource == null) {
                LOGGER.warn("The {} notification names the template {}, which does not exist",
                    notification.getEvent(), templatePath);
                return false;
            }
            // The template folder holds one rendering per channel; this notification simply has no email wording
            // when the folder carries no email child, which is a choice its author made, not an error
            final Resource emailRendering = templateResource.getChild(EMAIL_RENDERING);
            if (emailRendering == null) {
                LOGGER.info("The template {} has no {} rendering, so the {} notification was not emailed",
                    templatePath, EMAIL_RENDERING, notification.getEvent());
                return false;
            }
            final Node templateNode = Objects.requireNonNull(emailRendering.adaptTo(Node.class),
                "A template read from the repository is always backed by a node");
            final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
            final Email email = template.getEmailBuilder(variables(notification))
                .withRecipient(address, recipient.name())
                .build();
            // Sent as whatever the template actually has: demanding HTML would refuse a plain-text-only
            // template — and refuse it quietly, since the caller is a workflow that carries on regardless, so
            // the wording an author wrote would simply never arrive.
            EmailUtils.sendEmail(email, this.mailService);
            LOGGER.debug("Emailed the {} notification about {} to {}", notification.getEvent(),
                subject.getPath(), recipient.userId());
            return true;
        } catch (final RepositoryException | IOException | MessagingException | RuntimeException e) {
            LOGGER.error("The {} notification could not be emailed to {}: {}", notification.getEvent(),
                recipient.userId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Where this person can be emailed, as their account tells it: the {@code profile/email} Keycloak's sync
     * handler writes, and the profile editor lets people correct.
     *
     * @param recipient who to reach
     * @return their address, or {@code null} when the account does not carry one
     */
    private static String addressOf(final Recipient recipient)
    {
        final Resource profile = recipient.account().getChild("profile");
        return profile == null ? null : profile.getValueMap().get("email", String.class);
    }

    /**
     * What a template may interpolate: whatever the notification carries, plus the few things every message can
     * say about itself.
     *
     * @param notification what happened
     * @return the variables, as the strings a template substitutes
     */
    private static Map<String, String> variables(final NotificationContext notification)
    {
        final Map<String, String> variables = new HashMap<>();
        notification.getVariables().forEach((name, value) -> variables.put(name, Objects.toString(value, "")));
        // Always available, so that a template can name them without the workflow having to pass them
        variables.put("subjectPath", notification.getSubject().getPath());
        variables.put("subjectTitle", notification.getSubject().getValueMap().get("title", ""));
        variables.put("event", notification.getEvent());
        return variables;
    }
}
