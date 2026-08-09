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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.ResourceBundle;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.i18n.api.Messages;

/**
 * Serves a whole catalog of messages in one language, in one request.
 *
 * <p>Not the same thing as reading the catalog's nodes directly, which a client could already do. What this
 * adds is the answer rather than the ingredients: {@code /libs} merged with a deployment's {@code /apps}
 * overrides, and the locale fallback chain already walked, so a request for {@code fr-CA} receives the
 * {@code fr} messages it does not override and the default ones neither of them does. A client assembling
 * that itself would need one request per layer and would have to know which layers exist.</p>
 *
 * <p>It has to answer unauthenticated callers, because the sign-in and error pages render before anybody has
 * signed in and are exactly the pages that must not be in the wrong language. That is why it lives under
 * {@code /libs/iap}, which is already both readable by everyone and exempt from the authentication
 * requirement — a new top-level path would have been neither.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "iap/Messages" }, methods = { "GET" }, extensions = { "json" })
public class MessageCatalogServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 1L;

    /** Which catalog to serve; defaults to the interface strings, which is what a page shell wants. */
    private static final String CATALOG = "catalog";

    /** Which language to serve it in; defaults to the one the request asked for. */
    private static final String LOCALE = "locale";

    /** The region asking for the long, accented pseudo-locale, following Android and Chrome. */
    private static final String ACCENTED_REGION = "XA";

    /** The region asking for the short one. */
    private static final String SHORTENED_REGION = "XB";

    @Reference
    private transient ResourceBundleProvider bundles;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final String catalog = catalogOf(request);
        final Locale locale = localeOf(request);
        final PseudoLocale.Style pseudo = styleOf(locale);
        // A pseudo-locale is derived from the source language on the spot rather than stored. Derived, it
        // cannot drift from the catalog it is testing and cannot be partially written: every key is present,
        // so the fallback never fires and any plain English left on screen is provably a string that never
        // went through a catalog at all.
        final ResourceBundle bundle =
            this.bundles.getResourceBundle(catalog, pseudo == null ? locale : Locale.ENGLISH);
        final String body = serialize(catalog, locale, bundle, pseudo);

        // Every page needs this before it can render, so re-fetching it on each navigation is worth avoiding
        // — but a stale catalog shows the wrong words indefinitely, and somebody who has just reworded a
        // message expects to see it. Revalidation gives both: a conditional request, and a 304 with no body.
        final String tag = '"' + Integer.toHexString(body.hashCode()) + '"';
        response.setHeader("ETag", tag);
        response.setHeader("Cache-Control", "no-cache");
        if (tag.equals(request.getHeader("If-None-Match"))) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /**
     * Renders the catalog, sorted by key.
     *
     * <p>Sorted so that the same catalog always serializes to the same bytes, and therefore to the same
     * entity tag. Left in whatever order the bundle enumerates, the tag would change from one request to the
     * next and the revalidation above would quietly never hit — without any test failing.</p>
     *
     * @param catalog the catalog being served
     * @param locale the language it is being served in
     * @param bundle the messages, or {@code null} when no such catalog exists
     * @param pseudo how to disfigure each message, or {@code null} to serve it as written
     * @return the response body
     */
    private static String serialize(final String catalog, final Locale locale, final ResourceBundle bundle,
        final PseudoLocale.Style pseudo)
    {
        final JsonObjectBuilder messages = Json.createObjectBuilder();
        if (bundle != null) {
            Collections.list(bundle.getKeys()).stream().sorted()
                .forEach(key -> messages.add(key,
                    pseudo == null ? bundle.getString(key) : PseudoLocale.transform(bundle.getString(key), pseudo)));
        }
        return Json.createObjectBuilder()
            .add(CATALOG, catalog)
            .add(LOCALE, locale.toLanguageTag())
            .add("messages", messages)
            .build().toString();
    }

    /**
     * Which pseudo-locale, if any, is being asked for.
     *
     * <p>XA and XB are user-assigned region codes, so they can never collide with a real locale — the
     * convention Android and Chrome use for the same purpose.</p>
     *
     * @param locale the language being asked for
     * @return how to disfigure the messages, or {@code null} for an ordinary language
     */
    private static PseudoLocale.Style styleOf(final Locale locale)
    {
        if (!Locale.ENGLISH.getLanguage().equals(locale.getLanguage())) {
            return null;
        }
        if (ACCENTED_REGION.equals(locale.getCountry())) {
            return PseudoLocale.Style.ACCENTED;
        }
        if (SHORTENED_REGION.equals(locale.getCountry())) {
            return PseudoLocale.Style.SHORTENED;
        }
        return null;
    }

    private static String catalogOf(final SlingJakartaHttpServletRequest request)
    {
        final String requested = request.getParameter(CATALOG);
        return requested == null || requested.isBlank() ? Messages.INTERFACE : requested;
    }

    /**
     * The language to answer in: the one asked for by name, or else the one the request itself carries, which
     * Sling resolves from the {@code Accept-Language} header.
     *
     * <p>Naming one is the only way to reach a pseudo-locale. Sling resolves the header against the languages
     * it can find in the repository and falls back to the default when it recognises none, so a browser
     * announcing {@code en-XA} is answered in ordinary English — the fallback is doing its job, since nothing
     * is stored under that name. A caller that wants the check has to ask for it.</p>
     *
     * @param request the current request
     * @return a locale, never {@code null}
     */
    private static Locale localeOf(final SlingJakartaHttpServletRequest request)
    {
        final String requested = request.getParameter(LOCALE);
        if (requested == null || requested.isBlank()) {
            return request.getLocale();
        }
        return Locale.forLanguageTag(requested);
    }
}
