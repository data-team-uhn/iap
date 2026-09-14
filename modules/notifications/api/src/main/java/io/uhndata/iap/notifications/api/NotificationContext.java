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
 * The details about a notification: what happened, who it concerns, and how soon they should hear about it.
 *
 * <p>The subject is a resource, not a path. A notification is always about something: a submission, a review, a
 * task. Carrying the resource is what lets the rest of the system work from it. Recipient roles resolve against
 * it, a template reads its properties, a per-user setting can be scoped to it.</p>
 *
 * <p>There is no explicit recipient and delivery method here. A workflow says what happened and who it concerns.
 * A person's own settings say how they hear about it. One thing happening produces one notification and several
 * deliveries. The same notice of approval may be emailed to its author now, batched into the weekly digest for an
 * administrator, and left as an unread notice in the UI for the watcher who has turned email off.</p>
 *
 * <p>{@link #getUrgency() Urgency} is the workflow's side of that: a statement about the message, not about the
 * channel. "A decision was made" is {@link #IMMEDIATE_URGENCY}; "somebody replied to a comment" can wait to be
 * batched. It is a string rather than an enum because the vocabulary is open. A deployment adding a weekly digest
 * sent to a Slack channel names its own urgency in a workflow definition and registers a delivery that accepts it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NotificationContext
{
    /** Urgency for something the recipient should hear about as soon as it happens. */
    public static final String IMMEDIATE_URGENCY = "immediate";

    /** Urgency for something that can wait to be collected into a digest. */
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
     * @param subject what the notification is about
     * @return a builder
     */
    @NotNull
    public static Builder about(@NotNull final Resource subject)
    {
        return new Builder(subject);
    }

    /**
     * What the notification is about.
     *
     * @return the subject resource
     */
    @NotNull
    public Resource getSubject()
    {
        return this.subject;
    }

    /**
     * What happened to it, as the event name in the workflow, e.g. {@code approved}.
     *
     * @return the event name
     */
    @NotNull
    public String getEvent()
    {
        return this.event;
    }

    /**
     * Who caused it, as a repository user id, or {@code null} when nobody did, as when a deadline passes.
     *
     * @return the actor's user id, or {@code null}
     */
    @Nullable
    public String getActor()
    {
        return this.actor;
    }

    /**
     * How soon the recipient should hear about it. Advice from the workflow, not a direction to a channel.
     *
     * @return the urgency
     */
    @NotNull
    public String getUrgency()
    {
        return this.urgency;
    }

    /**
     * Where the template lives, or {@code null} when the caller left it to the delivery to decide.
     *
     * <p>Just a name. Deliveries usually read it as the path of a template folder holding one rendering per channel,
     * but that is not required. A delivery rendering its text some other way, from a bundle resource or a message
     * catalogue key, reads the same string its own way. A delivery that renders no text may ignore it.</p>
     *
     * @return where this notification's template is to be found, or {@code null}
     */
    @Nullable
    public String getTemplate()
    {
        return this.template;
    }

    /**
     * Anything else a template needs that cannot be read off the subject.
     *
     * @return the variables, empty when there are none
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
         * @param name the event name, e.g. {@code approved}
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
         * @param userId the actor's repository user id, or {@code null} when there is no explicit actor
         * @return this builder
         */
        @NotNull
        public Builder by(@Nullable final String userId)
        {
            this.actor = userId;
            return this;
        }

        /**
         * How soon the recipient should hear about it. Defaults to {@link #IMMEDIATE_URGENCY} when not said.
         *
         * @param level the implied urgency
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
         * Where the template lives, in whatever terms the deliveries that render it understand.
         *
         * @param template where to find it, e.g. the path of a template folder
         * @return this builder
         */
        @NotNull
        public Builder using(@Nullable final String template)
        {
            this.template = template;
            return this;
        }

        /**
         * One more thing a template may need, a variable and its value made available to the template (and the delivery
         * channel in general).
         *
         * @param name the variable name
         * @param value its value, or {@code null} to leave the variable out entirely
         * @return this builder
         */
        @NotNull
        public Builder with(@NotNull final String name, @Nullable final Object value)
        {
            // A value that is not there leaves the variable out rather than putting an empty one in. A
            // template asks `#if($note)`, and one that is always present but sometimes empty answers that
            // question wrongly. It is also the only reading the finished map allows, holding no nulls
            if (value != null) {
                this.variables.put(name, value);
            } else {
                this.variables.remove(name);
            }
            return this;
        }

        /**
         * The finished description.
         *
         * @return the context
         */
        @NotNull
        public NotificationContext build()
        {
            return new NotificationContext(this.subject, this.event, this.actor, this.urgency, this.template,
                this.variables);
        }
    }
}
