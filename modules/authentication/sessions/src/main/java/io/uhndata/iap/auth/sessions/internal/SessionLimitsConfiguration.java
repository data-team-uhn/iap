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
package io.uhndata.iap.auth.sessions.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * How long a signed-in browser stays signed in.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP session limits",
    description = "How long a signed-in browser stays signed in. The identity provider's own session must end at the "
        + "same times, or an ended session is resumed there without asking.")
public @interface SessionLimitsConfiguration
{
    /**
     * How long a session lasts without a request.
     *
     * @return the length in minutes
     */
    @AttributeDefinition(name = "Idle limit (minutes)",
        description = "A session ends this long after its last request. Requests made in the background, such as "
            + "polls for notifications, do not count.")
    int idleMinutes() default 30;

    /**
     * How long a session can last at all.
     *
     * @return the length in hours
     */
    @AttributeDefinition(name = "Maximum length (hours)",
        description = "A session ends this long after signing in, however busy it is.")
    int maximumHours() default 8;
}
