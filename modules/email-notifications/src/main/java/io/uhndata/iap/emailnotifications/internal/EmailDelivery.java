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
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;
import io.uhndata.iap.notifications.spi.NotificationDelivery;

/**
 * Emails a notification to its recipient as soon as it is offered.
 *
 * <p>
 * It declines a notification unless its urgency is {@link NotificationContext#IMMEDIATE_URGENCY}, the recipient's
 * account has an address at {@code profile/email}, and its template folder has an {@code email} child. That child is
 * a {@code mail:Template}.
 * </p>
 *
 * <p>
 * Besides the notification's own variables, the template can use four more:
 * </p>
 * <ul>
 * <li>{@code subjectResource}: the resource the notification is about</li>
 * <li>{@code subjectPath}: its path</li>
 * <li>{@code subjectTitle}: its {@code title} property, or an empty string</li>
 * <li>{@code event}: what happened, as the message name such as {@code approved}</li>
 * </ul>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = NotificationDelivery.class)
public class EmailDelivery implements NotificationDelivery
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDelivery.class);

    /** The child of a notification's template folder that holds the email template. */
    private static final String EMAIL_RENDERING = "email";

    // Dynamic and greedy, so that a mail service registered later with a higher ranking replaces this one at once
    @Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile MailService mailService;

    @Override
    public boolean deliver(final NotificationContext notification, final Recipient recipient)
    {
        if (!NotificationContext.IMMEDIATE_URGENCY.equals(notification.getUrgency())) {
            return false;
        }
        final String address = addressOf(recipient);
        if (address == null || address.isBlank()) {
            LOGGER.debug("A recipient has no email address, so the {} notification was not emailed",
                notification.getEvent());
            return false;
        }
        final String templatePath = notification.getTemplate();
        if (templatePath == null) {
            LOGGER.debug("The {} notification names no template, so there is nothing to email",
                notification.getEvent());
            return false;
        }
        return send(notification, recipient, address, templatePath);
    }

    /**
     * Builds the email and hands it to the mail service. A failure is logged and recorded.
     *
     * @param notification the notification to email
     * @param recipient the person to email
     * @param address the recipient's email address, not blank
     * @param templatePath the path of the notification's template folder
     * @return {@code true} if the email was handed to the mail service, {@code false} if the template is missing,
     *         has no email child, or fails
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
                ErrorLogger.logProblem("A notification names a template that is not there",
                    ErrorContext.of(EmailDelivery.class, "send")
                        .about(notification.getSubject().getPath())
                        .with("event", notification.getEvent())
                        .with("template", templatePath));
                return false;
            }
            // A template folder may have no email child, and then the notification sends no email
            final Resource emailRendering = templateResource.getChild(EMAIL_RENDERING);
            if (emailRendering == null) {
                LOGGER.debug("The template {} has no {} rendering, so the {} notification was not emailed",
                    templatePath, EMAIL_RENDERING, notification.getEvent());
                return false;
            }
            final Node templateNode = Objects.requireNonNull(emailRendering.adaptTo(Node.class),
                "A template read from the repository is always backed by a node");
            final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
            final Email email = template.getEmailBuilder(variables(notification))
                .withRecipient(address, recipient.name())
                .build();
            EmailUtils.sendEmail(email, this.mailService);
            // Handed over, not yet sent. EmailUtils records a send that fails later.
            LOGGER.debug("Handed the {} notification about {} to the mail service", notification.getEvent(),
                subject.getPath());
            return true;
        } catch (final RepositoryException | IOException | MessagingException | RuntimeException e) {
            LOGGER.error("The {} notification could not be emailed: {}", notification.getEvent(),
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(EmailDelivery.class, "send")
                .about(notification.getSubject().getPath())
                .with("event", notification.getEvent())
                .with("recipient", recipient.userId()));
            return false;
        }
    }

    /**
     * The email address on a person's account, at {@code profile/email}.
     *
     * @param recipient the person to email
     * @return the address, or {@code null} if the account has none
     */
    private static String addressOf(final Recipient recipient)
    {
        final Resource profile = recipient.account().getChild("profile");
        return profile == null ? null : profile.getValueMap().get("email", String.class);
    }

    /**
     * The variables a template can use: those the notification carries, and four about its subject and event. The
     * four replace a notification variable of the same name.
     *
     * @param notification the notification to email
     * @return the values, by variable name
     */
    private static Map<String, Object> variables(final NotificationContext notification)
    {
        final Map<String, Object> variables = new HashMap<>(notification.getVariables());
        variables.put("subjectResource", notification.getSubject());
        variables.put("subjectPath", notification.getSubject().getPath());
        variables.put("subjectTitle", notification.getSubject().getValueMap().get("title", ""));
        variables.put("event", notification.getEvent());
        return variables;
    }
}
