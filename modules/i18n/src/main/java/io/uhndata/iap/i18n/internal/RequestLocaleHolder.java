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
package io.uhndata.iap.i18n.internal;

import java.util.Optional;

/**
 * Where the current request's language lives while the request is being handled.
 *
 * <p>Deliberately not exported. Nothing outside this bundle should read the ambient request's language
 * directly: language is a property of who a piece of text is for, not of the thread that happens to be
 * producing it, and everything that needs an answer should get it from
 * {@link io.uhndata.iap.i18n.api.Locales}, which weighs this against the other factors. Leaving this
 * package-private makes that a fact rather than a comment.</p>
 *
 * <p>A thread-local is only safe because {@link RequestLocaleFilter} removes the value in a {@code finally}.
 * Threads are pooled, so a value left behind is handed to whoever is served next on that thread — and the
 * symptom, one request in a language chosen by a stranger, would be unreproducible.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class RequestLocaleHolder
{
    private static final ThreadLocal<RequestLocales> CURRENT = new ThreadLocal<>();

    private RequestLocaleHolder()
    {
        // Utility class, not meant to be instantiated
    }

    static void set(final RequestLocales locales)
    {
        CURRENT.set(locales);
    }

    static void clear()
    {
        CURRENT.remove();
    }

    static Optional<RequestLocales> get()
    {
        return Optional.ofNullable(CURRENT.get());
    }
}
