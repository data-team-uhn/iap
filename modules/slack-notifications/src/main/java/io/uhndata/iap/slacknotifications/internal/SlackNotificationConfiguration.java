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
package io.uhndata.iap.slacknotifications.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * One scheduled notification: when to post, where to post it, and what to include.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Scheduled Slack Notifications",
    description = "Configuration for sending regular chat notifications.")
public @interface SlackNotificationConfiguration
{
    /**
     * The name of this notification, which the log uses to tell notifications apart. Its scheduled job is named
     * after the component instead, so that two notifications sharing a name, or having none, keep a job each.
     *
     * @return a name such as {@code nightly}, best kept different for each notification
     */
    @AttributeDefinition(name = "Name")
    String name();

    /**
     * When the notification is posted.
     *
     * @return a Quartz cron expression such as {@code 0 0 0 * * ? *}, or {@code %ENV%} followed by the name of an
     *         environment variable holding one
     */
    @AttributeDefinition(name = "Schedule",
        description = "A Quartz-readable schedule expression determining when the notification job runs, for example "
            + "'0 0 0 * * ? *' for a nightly notification, or '0 0 9 ? * MON *' for a weekly Monday morning message. "
            + "A value starting with %ENV% is read from the named environment variable instead.")
    String schedule() default "%ENV%SLACK_NOTIFICATIONS_SCHEDULE";

    /**
     * Where to post the message.
     *
     * @return a webhook URL, or {@code %ENV%} followed by the name of an environment variable holding one
     */
    @AttributeDefinition(name = "Endpoint",
        description = "A webhook endpoint that will receive the message. A value starting with %ENV% is read from "
            + "the named environment variable instead, which is where a webhook address belongs: it is a secret.")
    String endpoint() default "%ENV%SLACK_NOTIFICATIONS_ENDPOINT";

    /**
     * The title of the message, posted above the attachments.
     *
     * @return the title, or an empty string for none
     */
    @AttributeDefinition(name = "Message title", description = "An optional title to include in the message.")
    String title() default "";

    /**
     * Which producers to ask for attachments.
     *
     * @return producer names such as {@code status}, or an empty array to ask every producer
     */
    @AttributeDefinition(name = "Include notifications",
        description = "Customize which notifications to include in the message. Leave empty to include all.")
    String[] include();

    /**
     * Extra parameters for the producers.
     *
     * @return {@code key=value} strings such as {@code statusReport.unprivileged=true}
     */
    @AttributeDefinition(name = "Extra parameters",
        description = "Optional extra parameters to pass to the notification producers."
            + " The expected values depend on each notification producer, but they must be in the key=value format.")
    String[] notificationParameters();

    /**
     * Whether to post nothing when there is nothing to report.
     *
     * @return {@code true} to post nothing when no producer has an attachment, {@code false} to post "Nothing to
     *         report" instead
     */
    @AttributeDefinition(name = "Don't post empty messages",
        description = "If the message ends up containing nothing at all, don't post anything."
            + " If this setting is false, then a 'Nothing to report' message will be sent in this case.")
    boolean skipEmpty() default true;
}
