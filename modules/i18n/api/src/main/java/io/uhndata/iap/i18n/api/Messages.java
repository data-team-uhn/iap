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
import java.util.SortedMap;

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
 * <p>Asking for a pseudo-locale — {@code en-XA}, {@code en-XB} — is answered with the source language
 * disfigured. That is on purpose, and it is what makes the build's check an assertion rather than a survey:
 * every route a catalogued message can take to a screen passes through here, so a message that comes out
 * plain is one that never went through a catalog at all. A caller disfiguring text itself would be the one
 * route that proves nothing, which is why this is not exposed.</p>
 *
 * <p>The exception is text {@link #translate} was handed and found no entry for, which is returned exactly
 * as it came. See that method for why: not everything passing through it is prose.</p>
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
     * A piece of shipped text in the reader's language, given the text as shipped.
     *
     * <p>For text that lives in the repository rather than in a catalog — a configured heading, a
     * description on a node. The catalog holds only the translations, keyed by the path of the property each
     * one translates, so the shipped text goes on being its own fallback and a half-translated deployment
     * renders rather than showing holes.</p>
     *
     * <p>Distinct from {@link #get} in what a miss means. A missing interface string is a bug and is answered
     * with its key, loudly; a property nobody has translated yet is the ordinary case and is answered with
     * what it says. Both share the awkward detail this hides: a Sling resource bundle answers a miss with the
     * key itself rather than by throwing, so every caller checking only for an exception replaces its text
     * with its own repository path.</p>
     *
     * <p>A miss is also the one thing a pseudo-locale leaves alone. Everything a page is built from arrives
     * here, not only its prose — resource types, identifiers, a list of language codes — and disfiguring
     * those breaks the page rather than testing it. Having an entry in the source-language catalog is
     * therefore what declares a property to be something a person reads.</p>
     *
     * @param catalog which catalog to look in, usually {@link #CONTENT}
     * @param key the message key, which for {@link #CONTENT} is the property's path
     * @param locale the language to answer in, or {@code null} for the instance's default
     * @param shipped the text as stored, used where no translation exists
     * @return the translation, or the shipped text
     */
    @NotNull
    String translate(@NotNull String catalog, @NotNull String key, @Nullable Locale locale,
        @NotNull String shipped);

    /**
     * Every message in a catalog, in one language.
     *
     * <p>The whole catalog rather than the keys one at a time, for callers serving it onward — a page shell
     * handing the browser its interface strings, say. The locale fallback chain is already walked, so a
     * request for {@code fr-CA} receives the {@code fr} messages it does not override and the default ones
     * neither of them does.</p>
     *
     * @param catalog which catalog to read, e.g. {@link #INTERFACE}
     * @param locale the language to answer in, or {@code null} for the instance's default
     * @return the messages, by key, sorted; empty where there is no such catalog
     */
    @NotNull
    SortedMap<String, String> getAll(@NotNull String catalog, @Nullable Locale locale);

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
