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
 * Where the stored notifications live and what they are called, for everything that reads or writes them.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class StoredNotifications
{
    /** The path of the homepage holding every stored notification. */
    public static final String PATH = "/Notifications";

    /** The resource type of the homepage. */
    public static final String HOMEPAGE_RESOURCE_TYPE = "notif/Homepage";

    /** The resource type of one stored notification. */
    public static final String RESOURCE_TYPE = "notif/Notification";

    /** The property naming who a notification is for. */
    public static final String RECIPIENT = "recipient";

    /** The property carrying the rendered one-sentence form a list shows. */
    public static final String LINE = "line";

    /** The property saying whether the recipient has seen it. */
    public static final String READ = "read";

    private StoredNotifications()
    {
        // Constants class
    }
}
