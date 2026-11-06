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
package io.uhndata.iap.slacknotifications.spi;

import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.jetbrains.annotations.NotNull;

/**
 * Provides attachments for a scheduled chat message. At each run of a scheduled notification, every producer it
 * includes is asked for its attachments, and all of them are posted to the webhook as one message.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SlackNotificationProducer
{
    /** The name of the JSON property holding the title of an attachment. */
    String TITLE_KEY = "title";

    /** The name of the JSON property holding the text of a message or of an attachment. */
    String TEXT_KEY = "text";

    /** The name of the JSON property holding the color of the bar beside an attachment. */
    String COLOR_KEY = "color";

    /** The color of an informational attachment, grey. */
    String INFO_COLOR = "999";

    /** The color of a success, green. */
    String SUCCESS_COLOR = "393";

    /** The color of a warning, yellow. */
    String WARNING_COLOR = "BA0";

    /** The color of an error, red. */
    String ERROR_COLOR = "900";

    /**
     * The name of this producer. A notification's configuration lists it to include this producer.
     *
     * @return a name such as {@code status}
     */
    @NotNull
    String getName();

    /**
     * Prepares this producer's attachments for the message. It must not throw: an exception loses the whole message,
     * the other producers' attachments included.
     *
     * @param extraParameters the notification's extra parameters, by name, empty if none are configured. Each producer
     *            reads the ones it knows, such as {@code statusReport.targetStatusLevel}
     * @return the attachments, each a JSON object with keys such as {@link #TITLE_KEY}, {@link #TEXT_KEY} and
     *         {@link #COLOR_KEY}, or an empty list if there is nothing to report
     */
    @NotNull
    List<JsonObject> prepareMessages(@NotNull Map<String, String> extraParameters);
}
