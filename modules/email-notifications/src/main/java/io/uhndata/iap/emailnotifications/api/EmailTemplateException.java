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
 * Thrown when a template cannot be turned into text: it does not parse, or it asks for something the caller did not
 * supply.
 *
 * <p>
 * <strong>Rendering fails loudly rather than producing what it can.</strong> A half-filled email is worse than no
 * email: it goes to a person, it carries the platform's name, and nothing downstream can tell that a paragraph is
 * missing or that a greeting reads "Dear ${name}". Whoever asked for the email is in a position to decide what to do
 * instead -- retry, fall back, or record the failure -- and this is unchecked so that decision can be made where it
 * belongs rather than at every call site in between.
 * </p>
 *
 * <p>
 * The message carries the position in the template, which is what a template author needs, and never the values,
 * which are somebody's answers.
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
     * @param message what could not be rendered, and where
     * @param cause the underlying template engine failure, may be {@code null}
     */
    public EmailTemplateException(@NotNull final String message, @Nullable final Throwable cause)
    {
        super(message, cause);
    }
}
