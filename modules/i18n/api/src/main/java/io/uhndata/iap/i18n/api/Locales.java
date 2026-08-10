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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which language to render something in.
 *
 * <p>Two of these methods answer that question and the rest are the factors they weigh. Reach for the first
 * two; the factors are exposed for preference screens, for diagnostics, and for callers that genuinely need
 * to know why an answer came out the way it did.</p>
 *
 * <p>The distinction that matters is <em>who</em> is being rendered for. Language looks like a property of
 * the request only for as long as a page is being rendered for the person who asked for it. It stops being
 * one the moment something is rendered for somebody else — an approval notice composed for a reviewer while
 * handling the submitter's request has to be in the reviewer's language, and anything that reads the
 * ambient request would confidently produce the submitter's. That is why there is no no-argument
 * {@code getLocale()}: the two cases are different calls rather than different threads, so the difference is
 * visible at the call site and to the compiler.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Locales
{
    /**
     * The language to render in for the person whose request is being handled.
     *
     * <p>Weighs, in order: a language named outright in the request, that person's stored preference, the
     * language their browser announced, and the deployment's default — narrowed to a language the deployment
     * actually offers.</p>
     *
     * <p>Ambient by nature, since "the current request" is what it is about. On a thread with no request —
     * a scheduled job, a worker handling something submitted earlier — it answers with the deployment's
     * default, which is right for nobody in particular and therefore the wrong thing to render a person's
     * notification in. Use {@link #getLocaleFor} there.</p>
     *
     * @return a language, never {@code null}
     */
    @NotNull
    Locale getReaderLocale();

    /**
     * The language to render in for the person who made a given request.
     *
     * <p>Preferred over {@link #getReaderLocale()} wherever a request is in hand, for the reason given on
     * {@link #getRequestLocale(HttpServletRequest)}: code holding a request has no business reaching through
     * ambient state to ask about it.</p>
     *
     * <p>Narrowed, and that is the difference from {@link #getRequestLocale(HttpServletRequest)}, which
     * reports what was asked for however unavailable it is. Anything answering a client has to report this
     * one: a request for a language the deployment does not offer is served in the default, and a response
     * that labelled those words with the language nobody could supply would be a lie a machine acts on —
     * a speech synthesiser reads English aloud with a German accent, and a hyphenator breaks the words in
     * the wrong places.</p>
     *
     * @param request the request to answer
     * @return a language this deployment actually offers, never {@code null}
     */
    @NotNull
    Locale getReaderLocale(@NotNull HttpServletRequest request);

    /**
     * The language to render in for a named person.
     *
     * <p>Deliberately ignores the current request, even where the person named happens to be the one who
     * made it. The tempting refinement — letting a fresh click win over a stale stored preference when the
     * two are the same person — is an invisible branch whose other case is the notification that goes out in
     * the wrong language. Code rendering for the current reader calls {@link #getReaderLocale} instead.</p>
     *
     * @param user the person the text is for
     * @return a language, never {@code null}
     */
    @NotNull
    Locale getLocaleFor(@NotNull Authorizable user);

    /**
     * The language the current request asked for, if it asked for one.
     *
     * <p>An ask, not an announcement: a language named in the URL, or one this browser was told to remember.
     * The {@code Accept-Language} header is not an ask — it is what a browser announces everywhere by
     * default, and it is available from the request itself.</p>
     *
     * @return the language asked for, or empty where none was asked for or there is no request
     */
    @NotNull
    Optional<Locale> getRequestLocale();

    /**
     * The language a given request asked for, if it asked for one.
     *
     * <p>Preferred over {@link #getRequestLocale()} wherever a request is in hand. Reaching through ambient
     * state to ask about the request you are already holding is both slower to understand and wrong on any
     * thread you did not expect to be on.</p>
     *
     * @param request the request to read
     * @return the language asked for, or empty where none was asked for
     */
    @NotNull
    Optional<Locale> getRequestLocale(@NotNull HttpServletRequest request);

    /**
     * A person's stored language preference, if they have set one.
     *
     * @param user the person whose preference to read
     * @return their preference, or empty where they have not set one
     */
    @NotNull
    Optional<Locale> getUserPreferredLocale(@NotNull Authorizable user);

    /**
     * Whether a language is written right to left.
     *
     * <p>Asked of the language itself rather than looked up in a list of them, so Arabic, Hebrew, Persian and
     * Urdu are all answered correctly without anybody having had to think of them.</p>
     *
     * @param locale the language to ask about
     * @return {@code true} where text in it runs right to left
     */
    boolean isRightToLeft(@NotNull Locale locale);

    /**
     * The languages this deployment offers.
     *
     * <p>What a language switcher should list, and what every other answer here is narrowed to: resolving to
     * a language nothing has been translated into is a real outcome otherwise, and an unhelpful one.</p>
     *
     * @return the languages on offer, the first of which is the deployment's default; never empty
     */
    @NotNull
    List<Locale> getAvailableLocales();

    /**
     * The language the server itself writes in — log messages, diagnostics, anything addressed to whoever is
     * running the deployment rather than to a person using it.
     *
     * <p>Configured rather than taken from the JVM, so that a server whose default happens to be {@code
     * fr_CA} does not quietly write half of one log file in French alongside everything the platform's
     * libraries write in English. One language per log is worth more than any particular choice of it.</p>
     *
     * @return the language to write server-facing text in, never {@code null}
     */
    @NotNull
    Locale getSystemLocale();
}
