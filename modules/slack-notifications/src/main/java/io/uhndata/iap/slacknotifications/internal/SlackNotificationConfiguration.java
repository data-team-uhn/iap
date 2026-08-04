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
     * The name of this notification, which also names its scheduled job.
     *
     * @return a simple string
     */
    @AttributeDefinition(name = "Name")
    String name();

    /**
     * When the notification job runs.
     *
     * @return a Quartz-readable schedule expression, or an {@code %ENV%}-prefixed environment variable holding one
     */
    @AttributeDefinition(name = "Schedule",
        description = "A Quartz-readable schedule expression determining when the notification job runs, for example "
            + "'0 0 0 * * ? *' for a nightly notification, or '0 0 9 ? * MON *' for a weekly Monday morning message. "
            + "A value starting with %ENV% is read from the named environment variable instead.")
    String schedule() default "%ENV%SLACK_NOTIFICATIONS_SCHEDULE";

    /**
     * Where to post the message.
     *
     * @return a webhook address, or an {@code %ENV%}-prefixed environment variable holding one
     */
    @AttributeDefinition(name = "Endpoint",
        description = "A webhook endpoint that will receive the message. A value starting with %ENV% is read from "
            + "the named environment variable instead, which is where a webhook address belongs: it is a secret.")
    String endpoint() default "%ENV%SLACK_NOTIFICATIONS_ENDPOINT";

    /**
     * An optional title to include in the message.
     *
     * @return a simple string, empty for no title
     */
    @AttributeDefinition(name = "Message title", description = "An optional title to include in the message.")
    String title() default "";

    /**
     * Which notification producers to include.
     *
     * @return the names of the producers to use, empty to use all of them
     */
    @AttributeDefinition(name = "Include notifications",
        description = "Customize which notifications to include in the message. Leave empty to include all.")
    String[] include();

    /**
     * Extra parameters to pass to the notification producers.
     *
     * @return parameters in the {@code key=value} format
     */
    @AttributeDefinition(name = "Extra parameters",
        description = "Optional extra parameters to pass to the notification producers."
            + " The expected values depend on each notification producer, but they must be in the key=value format.")
    String[] notificationParameters();

    /**
     * Whether to stay quiet when there is nothing to report.
     *
     * @return {@code true} to post nothing at all when no producer had anything to say
     */
    @AttributeDefinition(name = "Don't post empty messages",
        description = "If the message ends up containing nothing at all, don't post anything."
            + " If this setting is false, then a 'Nothing to report' message will be sent in this case.")
    boolean skipEmpty() default true;
}
