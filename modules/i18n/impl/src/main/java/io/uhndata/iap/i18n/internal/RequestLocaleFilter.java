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

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.i18n.api.Locales;

/**
 * Notices what each request has to say about language, and remembers a reader's choice.
 *
 * <p>Two jobs, both of which have to happen before anything renders. It puts the request's languages where
 * {@link io.uhndata.iap.i18n.api.Locales} can weigh them, for the sake of code too far down a call stack to
 * have been handed the request — a serializer, a Sling Model adapted from a resource. And where a language
 * was named outright, it writes a cookie, so that the choice survives the one navigation that is certain to
 * happen next: signing in. Without that, somebody switches to French, signs in, and lands back in
 * English.</p>
 *
 * <p>A cookie rather than browser storage because the server renders configured text into the page's
 * {@code <meta>} tags and cannot read browser storage. It is the only place a choice can be kept that both
 * halves of a page can see, and one answer read by both is what stops a heading and the form beneath it
 * disagreeing about what language the page is in.</p>
 *
 * <p>Following somebody else's link with a language in it therefore also sets the cookie, since a request
 * cannot tell a click apart from a link that was shared. That is accepted rather than worked around: it is a
 * cookie rather than an account preference, it is visible immediately, and it takes one click to undo.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Filter.class,
    property = {
        // Ahead of anything that renders, since all of it may want to know the language
        "service.ranking:Integer=100",
        "sling.filter.scope=REQUEST"
    })
public class RequestLocaleFilter implements Filter
{
    /** How long a browser is asked to remember a choice: long enough to be a preference rather than a mood. */
    private static final int A_YEAR = 365 * 24 * 60 * 60;

    @Reference
    private Locales localeService;

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
        throws IOException, ServletException
    {
        if (!(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }

        RequestLocaleHolder.set(RequestLocales.from(http));
        try {
            // The resolved language, not merely the one asked for, and left on the request itself so that
            // bundles which cannot depend on this one can still read it. The page shell renders it into
            // <html lang> and <html dir> before a single script has run, so the page arrives the right way
            // round rather than turning around once the interface strings catch up.
            final Locale resolved = this.localeService.getReaderLocale();
            http.setAttribute(RequestLocales.ATTRIBUTE, resolved);
            http.setAttribute(RequestLocales.DIRECTION_ATTRIBUTE,
                this.localeService.isRightToLeft(resolved) ? "rtl" : "ltr");
            remember(http, response);
            chain.doFilter(request, response);
        } finally {
            // In a finally without exception: threads are pooled, so a value left behind here is handed to
            // whoever is served next on this thread, and a page in a stranger's language would never
            // reproduce
            RequestLocaleHolder.clear();
        }
    }

    /**
     * Writes the choice back to the browser, where this request made one.
     *
     * <p>Only where the language was named in the URL. A request whose language came from the cookie has
     * made no new choice, and rewriting it on every request would extend the cookie's life indefinitely from
     * a single visit.</p>
     *
     * @param request the request being handled
     * @param response the response to set the cookie on
     */
    private void remember(final HttpServletRequest request, final ServletResponse response)
    {
        final Locale named = RequestLocales.parse(request.getParameter(RequestLocales.LOCALE)).orElse(null);
        if (named == null || !(response instanceof HttpServletResponse http)) {
            return;
        }
        final Cookie cookie = new Cookie(RequestLocales.COOKIE, named.toLanguageTag());
        cookie.setPath("/");
        cookie.setMaxAge(A_YEAR);
        // Nothing in the browser needs to read this: the server resolves the language and answers with it,
        // so there is one implementation of the rules rather than two that drift apart
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setSecure(request.isSecure());
        http.addCookie(cookie);
    }
}
