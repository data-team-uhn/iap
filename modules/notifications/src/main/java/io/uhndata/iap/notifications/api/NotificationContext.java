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
 * What happened, who it concerns and how soon they should hear about it: everything a notification is, before
 * anybody decides how to deliver it.
 *
 * <p>
 * <strong>The subject is a resource, not a path.</strong> A notification is always about something — a
 * submission, a review, a task — and carrying the resource rather than its path is what lets the rest of the
 * system work from it: recipient roles are resolved against <em>this</em> subject, a template reads its
 * properties, and a per-user setting can be scoped to it. A path would make each of those a second lookup, and
 * the one place that already has the resource is the one place that would not have to do it.
 * </p>
 *
 * <p>
 * <strong>There is no recipient here, deliberately.</strong> One thing happening produces one notification and
 * several deliveries: the same approval may be emailed to its author now, batched into tomorrow's digest for a
 * watching administrator, and left as an unread marker for somebody who has turned email off. Naming a recipient
 * on the notification itself would force that decision upwards, into the workflow definition, which is exactly
 * where it does not belong — a workflow says <em>what happened and who it concerns</em>, and a person's own
 * settings say how they hear about it.
 * </p>
 *
 * <p>
 * {@link #getUrgency() Urgency} is the workflow's side of that conversation: a statement about the message, not
 * about the channel. "A decision was made" is {@link #IMMEDIATE}; "somebody replied to a comment" can wait to be
 * batched. What a given person does with that is theirs.
 * </p>
 *
 * <p>
 * It is a string rather than an enum because the vocabulary is open: {@link #IMMEDIATE} and {@link #BATCHED} are
 * the two the platform ships, but a deployment adding a weekly digest names its own urgency in a workflow
 * definition and registers a delivery that accepts it, with nothing to change here. An enum would make every such
 * word a change to this bundle, and would have to decide what to do with one it did not recognise — where a
 * string simply reaches the deliveries, all of which decline it, which is already the correct outcome.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class NotificationContext
{
    /** Urgency for something the recipient should hear about as soon as it happens. */
    public static final String IMMEDIATE = "immediate";

    /** Urgency for something that can wait to be collected into a digest. */
    public static final String BATCHED = "batched";

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
     * Starts describing a notification about something.
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
     * What happened to it, as the name a template and a user setting both key on, e.g. {@code approved}.
     *
     * @return the event name
     */
    @NotNull
    public String getEvent()
    {
        return this.event;
    }

    /**
     * Who caused it, as a repository user id, or {@code null} when nobody did — a deadline passing, say.
     *
     * @return the actor's user id, or {@code null}
     */
    @Nullable
    public String getActor()
    {
        return this.actor;
    }

    /**
     * How soon the recipient should hear about it: {@link #IMMEDIATE}, {@link #BATCHED}, or whatever else a
     * deployment names. Advice from the workflow, not an instruction to a channel.
     *
     * @return the urgency
     */
    @NotNull
    public String getUrgency()
    {
        return this.urgency;
    }

    /**
     * Where the wording lives, or {@code null} when the caller left it to the delivery to decide.
     *
     * <p>Deliberately just a name. The deliveries that ship read it as the path of a template folder holding one
     * rendering per channel, but nothing here requires that: a delivery that renders its text some other way —
     * naming a status producer, a bundle resource, a message catalogue key — reads the same string its own way.
     * It is here rather than passed to each delivery because it is stated by the same thing that states the event
     * and the urgency, one workflow node, and a delivery that renders no text simply ignores it.</p>
     *
     * @return where this notification's wording is to be found, or {@code null}
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

        private String urgency = IMMEDIATE;

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
         * @param userId the actor's repository user id, or {@code null} when nobody did
         * @return this builder
         */
        @NotNull
        public Builder by(@Nullable final String userId)
        {
            this.actor = userId;
            return this;
        }

        /**
         * How soon the recipient should hear about it. Defaults to {@link #IMMEDIATE} when not said: a
         * notification nobody thought about is more likely to be one that matters than one that does not.
         *
         * @param level the urgency
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
         * Where the wording lives, in whatever terms the deliveries that will render it understand.
         *
         * @param template where to find the wording, e.g. the path of a template folder
         * @return this builder
         */
        @NotNull
        public Builder using(@Nullable final String template)
        {
            this.template = template;
            return this;
        }

        /**
         * One more thing a template may need.
         *
         * @param name the variable name
         * @param value its value
         * @return this builder
         */
        @NotNull
        public Builder with(@NotNull final String name, @Nullable final Object value)
        {
            this.variables.put(name, value);
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
