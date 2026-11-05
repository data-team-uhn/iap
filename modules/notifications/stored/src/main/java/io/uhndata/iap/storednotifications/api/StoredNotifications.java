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
package io.uhndata.iap.storednotifications.api;

/**
 * The path, resource types and property names of the stored notifications.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class StoredNotifications
{
    /** The path of the homepage holding every stored notification. */
    public static final String HOMEPAGE_PATH = "/Notifications";

    /** The resource type of the homepage. */
    public static final String HOMEPAGE_RESOURCE_TYPE = "notif/Homepage";

    /** The resource type of one stored notification. */
    public static final String NOTIFICATION_RESOURCE_TYPE = "notif/Notification";

    /** The property holding the user id of the person a notification is for. */
    public static final String RECIPIENT_PROPERTY = "recipient";

    /** The property holding the sentence shown to the recipient, rendered when the notification was stored. */
    public static final String MESSAGE_PROPERTY = "message";

    /** The property of a template folder holding that sentence, with {@code ${name}} placeholders. */
    public static final String UI_MESSAGE_PROPERTY = "uiMessage";

    /** The property saying whether the recipient has marked a notification read. */
    public static final String READ_PROPERTY = "read";

    private StoredNotifications()
    {
        // Constants class
    }
}
