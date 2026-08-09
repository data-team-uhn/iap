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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * What one request had to say about language.
 *
 * <p>Two separate things, kept separate: what the request <em>asked</em> for — a language named in the URL,
 * or one this browser was told to remember — and what it merely <em>announced</em>, which is the
 * {@code Accept-Language} every browser sends everywhere whether or not anybody chose it. Collapsing them
 * would make a default indistinguishable from a decision, and a stored preference could then never
 * outrank a header nobody set on purpose.</p>
 *
 * @param chosen the language the request asked for, if it asked for one
 * @param announced the language the browser announced
 * @version $Id$
 * @since 0.1.0
 */
record RequestLocales(Optional<Locale> chosen, Locale announced)
{
    /** The request parameter, and the cookie, naming a language outright. */
    static final String LOCALE = "locale";

    /** Remembers a choice made before there is an account to store it against. */
    static final String COOKIE = "iap.locale";

    /** Where the filter leaves the direction that language reads in, as {@code ltr} or {@code rtl}. */
    static final String DIRECTION_ATTRIBUTE = "io.uhndata.iap.i18n.direction";

    /**
     * Where the filter leaves the language it worked out, for code in bundles that cannot depend on this one.
     *
     * <p>A request attribute rather than a service call because this bundle already depends on java-utils,
     * so java-utils cannot depend back on it. Only the name is shared; the rules for working the language out
     * stay here, which is the part that would actually hurt to have two of. The proper fix is an interfaces-
     * only bundle both can depend on, and that is worth doing when the second such caller appears.</p>
     */
    static final String ATTRIBUTE = "io.uhndata.iap.i18n.locale";

    /**
     * What a language tag is allowed to look like. Anything else is somebody else's idea rather than a
     * reader's: this value is written back out in a cookie and echoed in responses, so it is checked on the
     * way in rather than trusted for having arrived in a request.
     */
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}");

    /**
     * Reads what a request has to say about language.
     *
     * <p>A language named in the URL outranks one remembered in a cookie, since it is the more recent of the
     * two acts and the only one that could have been performed just now.</p>
     *
     * @param request the request to read
     * @return what it asked for and what it announced
     */
    static RequestLocales from(final HttpServletRequest request)
    {
        final Optional<Locale> named = parse(request.getParameter(LOCALE));
        return new RequestLocales(named.isPresent() ? named : remembered(request), request.getLocale());
    }

    /**
     * The language this browser was previously told to remember, if any.
     *
     * @param request the request to read
     * @return the remembered language, or empty
     */
    static Optional<Locale> remembered(final HttpServletRequest request)
    {
        final Cookie[] cookies = request.getCookies();
        return cookies == null ? Optional.empty()
            : Arrays.stream(cookies)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .flatMap(value -> parse(value).stream())
                .findFirst();
    }

    /**
     * Turns a language tag into a language, where it is one.
     *
     * @param tag the tag to read, possibly {@code null} or nonsense
     * @return the language, or empty where the tag was missing or malformed
     */
    static Optional<Locale> parse(final String tag)
    {
        if (tag == null || !WELL_FORMED.matcher(tag).matches()) {
            return Optional.empty();
        }
        final Locale locale = Locale.forLanguageTag(tag);
        return locale.getLanguage().isEmpty() ? Optional.empty() : Optional.of(locale);
    }
}
