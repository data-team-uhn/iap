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
package io.uhndata.iap.emailnotifications.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when an email template cannot be filled in: it does not parse, or it uses a variable that nobody supplied.
 * No email is sent from a template that fails.
 *
 * <p>
 * The message is Velocity's account of the failure, which names the line and column in the template.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class EmailTemplateException extends RuntimeException
{
    private static final long serialVersionUID = 5893871281396215304L;

    /**
     * Constructor.
     *
     * @param message what could not be filled in, and where in the template
     * @param cause the Velocity failure, or {@code null} if there is none
     */
    public EmailTemplateException(@NotNull final String message, @Nullable final Throwable cause)
    {
        super(message, cause);
    }
}
