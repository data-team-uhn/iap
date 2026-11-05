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
package io.uhndata.iap.notifications.api;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a notification says: what happened, to which resource, who caused it, and how soon people should hear about
 * it.
 *
 * <p>The subject is a resource, not a path. Special names such as {@code @creator} resolve against it, and a
 * template reads its properties.</p>
 *
 * <p>It names no recipient and no channel. The same notification is offered to every delivery, for each person it
 * concerns, and each delivery decides whether and how to carry it. Several deliveries can carry the same
 * notification to the same person.</p>
 *
 * <p>{@link #getUrgency() Urgency} describes the message, not the channel. "A decision was made" is
 * {@link #IMMEDIATE_URGENCY}, and "somebody replied to a comment" can be {@link #BATCHED_URGENCY}. It is a string,
 * not an enum, because the vocabulary is open. A deployment can name its own urgency in a workflow definition and
 * register a delivery that accepts it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NotificationContext
{
    /** Urgency for something the recipient should hear about as soon as it happens. */
    public static final String IMMEDIATE_URGENCY = "immediate";

    /** Urgency for something that can wait, and be told together with others later. */
    public static final String BATCHED_URGENCY = "batched";

    private final Resource subject;

    private final String event;

    private final String actor;

    private final String urgency;

    private final String template;

    private final Map<String, Object> variables;

    private NotificationContext(final Resource subject, final String event, final String actor,
        final String urgency, final String template, final Map<String, Object> variables)
    {
        this.subject = subject;
        this.event = event;
        this.actor = actor;
        this.urgency = urgency;
        this.template = template;
        this.variables = Map.copyOf(variables);
    }

    /**
     * Starts describing a notification about a resource.
     *
     * @param subject the resource the notification is about, such as a submission
     * @return a builder
     */
    @NotNull
    public static Builder about(@NotNull final Resource subject)
    {
        return new Builder(subject);
    }

    /**
     * The resource the notification is about.
     *
     * @return the resource given to {@link #about}
     */
    @NotNull
    public Resource getSubject()
    {
        return this.subject;
    }

    /**
     * What happened to the subject.
     *
     * @return the event name, such as {@code approved}; empty if the notification was described without one
     */
    @NotNull
    public String getEvent()
    {
        return this.event;
    }

    /**
     * Who caused it.
     *
     * @return the user id of the person who caused it, or {@code null} if nobody did, as when a deadline passes
     */
    @Nullable
    public String getActor()
    {
        return this.actor;
    }

    /**
     * How soon the recipients should hear about it. It is advice to the deliveries, and each decides what to do with
     * it.
     *
     * @return the urgency, such as {@link #IMMEDIATE_URGENCY}, which is the default
     */
    @NotNull
    public String getUrgency()
    {
        return this.urgency;
    }

    /**
     * The name of the notification's template. Each delivery reads it in its own way. A delivery that reads its text
     * from the repository takes it as the path of a template folder, such as
     * {@code /libs/iap/notificationTemplates/timeOffApproved}. A delivery that renders no text may ignore it.
     *
     * @return the template's name, or {@code null} if the notification names none
     */
    @Nullable
    public String getTemplate()
    {
        return this.template;
    }

    /**
     * Values for the template that cannot be read off the subject, such as the note a person wrote with a decision.
     *
     * @return the values by variable name, none of them {@code null}; empty if there are none. The map cannot be
     *         changed.
     */
    @NotNull
    public Map<String, Object> getVariables()
    {
        return this.variables;
    }

    /**
     * Builds a {@link NotificationContext}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final Resource subject;

        private final Map<String, Object> variables = new HashMap<>();

        private String event = "";

        private String actor;

        private String urgency = IMMEDIATE_URGENCY;

        private String template;

        private Builder(final Resource subject)
        {
            this.subject = subject;
        }

        /**
         * What happened to the subject.
         *
         * @param name the event name, such as {@code approved}
         * @return this builder
         */
        @NotNull
        public Builder becauseOf(@NotNull final String name)
        {
            this.event = name;
            return this;
        }

        /**
         * Who caused it.
         *
         * @param userId the user id of the person who caused it, or {@code null} if nobody did
         * @return this builder
         */
        @NotNull
        public Builder by(@Nullable final String userId)
        {
            this.actor = userId;
            return this;
        }

        /**
         * How soon the recipients should hear about it.
         *
         * @param level the urgency, such as {@link #BATCHED_URGENCY}; {@code null} or blank keeps
         *            {@link #IMMEDIATE_URGENCY}
         * @return this builder
         */
        @NotNull
        public Builder urgency(@Nullable final String level)
        {
            if (level != null && !level.isBlank()) {
                this.urgency = level;
            }
            return this;
        }

        /**
         * Names the notification's template.
         *
         * @param template the template's name, such as the path of a template folder; {@code null} for none
         * @return this builder
         */
        @NotNull
        public Builder using(@Nullable final String template)
        {
            this.template = template;
            return this;
        }

        /**
         * Adds a value for the template.
         *
         * @param name the variable name, such as {@code outcomeNote}
         * @param value its value, of any type; {@code null} leaves the variable out, and removes a value given before
         * @return this builder
         */
        @NotNull
        public Builder with(@NotNull final String name, @Nullable final Object value)
        {
            if (value != null) {
                this.variables.put(name, value);
            } else {
                this.variables.remove(name);
            }
            return this;
        }

        /**
         * Builds the notification.
         *
         * @return the notification
         */
        @NotNull
        public NotificationContext build()
        {
            return new NotificationContext(this.subject, this.event, this.actor, this.urgency, this.template,
                this.variables);
        }
    }
}
