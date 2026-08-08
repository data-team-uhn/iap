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
package io.uhndata.iap.i18n.api;

import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Renders a message in a reader's language.
 *
 * <p>Server-side translation is not optional here, however much of the interface is rendered in the browser:
 * the sign-in and error pages are served before any application code runs, and emails, chat notifications and
 * generated documentation have no browser at all. So this exists whatever the client does, which is also why
 * asking clients to render messages from a key and arguments would save nothing.</p>
 *
 * <p>Messages are looked up in a <em>catalog</em>, and there are two of them for a reason that is not
 * cosmetic. Interface strings are written by developers, keyed by a name they choose, and every key should be
 * referenced by some code — an unreferenced one is dead and a missing one is a bug, both of which the build
 * can check. Translations of content are keyed by the path of the property they translate, referenced by no
 * code at all, and are added and removed as content is. One catalog holding both could not be checked at
 * all.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Messages
{
    /** The catalog of developer-authored interface strings, keyed by dotted names. */
    String INTERFACE = "iap.interface";

    /**
     * The catalog of translations for shipped content, keyed by the path of the property each one translates.
     * Separate from {@link #INTERFACE} so that the build's unreferenced-key check can apply to that one
     * without flagging every entry in this one.
     */
    String CONTENT = "iap.content";

    /**
     * The message for a key, as written, with no arguments substituted.
     *
     * @param catalog which catalog to look in, e.g. {@link #INTERFACE}
     * @param key the message key
     * @param locale the language to answer in, or {@code null} for the instance's default
     * @return the message, or the key itself when the catalog has no such message
     */
    @NotNull
    String get(@NotNull String catalog, @NotNull String key, @Nullable Locale locale);

    /**
     * The message for a key, with its arguments substituted.
     *
     * <p>Arguments are named rather than numbered, so a translator may reorder them, use one twice, or leave
     * one out — all of which real translations do, and none of which positional arguments allow.</p>
     *
     * @param catalog which catalog to look in, e.g. {@link #INTERFACE}
     * @param key the message key
     * @param locale the language to answer in, or {@code null} for the instance's default
     * @param arguments the values the message names, which may be empty
     * @return the formatted message, or the key itself when the catalog has no such message
     */
    @NotNull
    String format(@NotNull String catalog, @NotNull String key, @Nullable Locale locale,
        @NotNull Map<String, Object> arguments);
}
