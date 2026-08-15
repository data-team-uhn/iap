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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

import com.ibm.icu.util.ULocale;
import io.uhndata.iap.i18n.api.Locales;

/**
 * Default implementation of {@link Locales}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Locales.class)
@Designate(ocd = LocalesConfiguration.class)
public class LocalesImpl implements Locales
{
    private List<Locale> available;

    private Locale system;

    @Activate
    void activate(final LocalesConfiguration configuration)
    {
        final List<Locale> offered = Arrays.stream(configuration.availableLocales())
            .flatMap(tag -> RequestLocales.parse(tag).stream())
            .distinct()
            .toList();
        // A deployment that offers nothing still has to answer somebody, and English is what this platform's
        // own strings are written in
        this.available = offered.isEmpty() ? List.of(Locale.ENGLISH) : offered;
        this.system = RequestLocales.parse(configuration.systemLocale()).orElseGet(Locale::getDefault);
    }

    @Override
    @NotNull
    public Locale getReaderLocale()
    {
        // The stored preference belongs between these two, and is not consulted yet: nothing stores one.
        // See getUserPreferredLocale, which says so rather than quietly answering the default.
        return narrow(RequestLocaleHolder.get()
            .map(locales -> locales.chosen().orElse(locales.announced()))
            .orElseGet(this::deployment));
    }

    @Override
    @NotNull
    public Locale getReaderLocale(@NotNull final HttpServletRequest request)
    {
        final RequestLocales asked = RequestLocales.from(request);
        return narrow(asked.chosen().orElseGet(asked::announced));
    }

    @Override
    @NotNull
    public Locale getLocaleFor(@NotNull final Authorizable user)
    {
        throw new UnsupportedOperationException(
            "Rendering for a named person needs a stored language preference, which nothing writes yet");
    }

    @Override
    @NotNull
    public Optional<Locale> getRequestLocale()
    {
        return RequestLocaleHolder.get().flatMap(RequestLocales::chosen);
    }

    @Override
    @NotNull
    public Optional<Locale> getRequestLocale(@NotNull final HttpServletRequest request)
    {
        return RequestLocales.from(request).chosen();
    }

    @Override
    @NotNull
    public Optional<Locale> getUserPreferredLocale(@NotNull final Authorizable user)
    {
        throw new UnsupportedOperationException("Nothing stores a language preference against an account yet");
    }

    @Override
    public boolean isRightToLeft(@NotNull final Locale locale)
    {
        // The mirrored pseudo-locale is English underneath, so nothing but this knows it should turn around
        if (PseudoLocale.styleOf(locale) == PseudoLocale.Style.SHORTENED) {
            return true;
        }
        return ULocale.forLocale(locale).isRightToLeft();
    }

    @Override
    @NotNull
    public List<Locale> getAvailableLocales()
    {
        return this.available;
    }

    @Override
    @NotNull
    public Locale getSystemLocale()
    {
        return this.system;
    }

    /**
     * The nearest language on offer to the one asked for.
     *
     * <p>Asking for {@code fr-CA} where only {@code fr} is offered should be answered in French rather than
     * refused; asking for something offered in no form at all is answered in the deployment's default, since
     * a page in a language nothing was translated into helps nobody.</p>
     *
     * <p>Pseudo-locales pass through untouched. Nothing is stored under those names by design — they are
     * derived from the source language on request — so narrowing would resolve the build's own check away.</p>
     *
     * @param requested the language asked for
     * @return the language to answer in
     */
    private Locale narrow(final Locale requested)
    {
        if (PseudoLocale.styleOf(requested) != null) {
            return requested;
        }
        final Locale matched = Locale.lookup(
            List.of(new Locale.LanguageRange(requested.toLanguageTag())), this.available);
        return matched == null ? deployment() : matched;
    }

    /** The language a deployment falls back to: the first it says it offers. */
    private Locale deployment()
    {
        return this.available.get(0);
    }
}
