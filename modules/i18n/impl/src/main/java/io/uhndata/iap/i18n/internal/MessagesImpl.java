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

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.sling.i18n.ResourceBundleProvider;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.icu.text.MessageFormat;
import io.uhndata.iap.i18n.api.Messages;

/**
 * Messages read from the repository and formatted by ICU.
 *
 * <p>The split is deliberate. Sling's {@code ResourceBundleProvider} finds the catalog: it merges
 * {@code /libs} with {@code /apps} so a deployment can override a single message without a rebuild, walks the
 * locale fallback chain, caches, and invalidates that cache when the content changes — and it does all of it
 * without a request, which emails and the workflow engine need. ICU then does the formatting, because Sling's
 * own is {@code java.text.MessageFormat}, whose idea of a plural is a numeric range rather than a language's
 * grammar.</p>
 *
 * <p>Two formatters in one application would be worse than either: the failure mode is a message that is only
 * wrong in the languages nobody on the team reads.</p>
 *
 * <p>This is also the single place a pseudo-locale is applied. It was worth moving here from the servlet that
 * first needed it: applied at one exit, the check only ever covered text leaving by that exit, and text
 * rendered by the server came out plain — indistinguishable, to the check, from a string that was never
 * translatable.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = Messages.class)
public class MessagesImpl implements Messages
{
    private static final Logger LOGGER = LoggerFactory.getLogger(MessagesImpl.class);

    @Reference
    private ResourceBundleProvider bundles;

    @Override
    public String get(final String catalog, final String key, final Locale locale)
    {
        final PseudoLocale.Style pseudo = PseudoLocale.styleOf(locale);
        final String message = lookUp(catalog, key, source(locale, pseudo));
        if (message == null) {
            // Answering with the key is what makes a missing message obvious on screen without being fatal.
            // The build's key check is what stops one reaching a release; this is only the runtime behavior.
            // Left undisfigured on purpose: a pseudo-locale exists to make untranslatable text stand out, and
            // a key dressed up as one would blend in with everything around it.
            LOGGER.debug("No message [{}] in [{}] for [{}]", key, catalog, locale);
            return key;
        }
        return disfigure(message, pseudo);
    }

    @Override
    public String translate(final String catalog, final String key, final Locale locale, final String shipped)
    {
        final PseudoLocale.Style pseudo = PseudoLocale.styleOf(locale);
        final String translated = lookUp(catalog, key, source(locale, pseudo));
        // Text nobody has catalogued is returned exactly as it was, pseudo-locale or not, and that is the
        // load-bearing difference from an interface message. What arrives here is every string property of
        // whatever is being rendered -- resource types, identifiers, a list of language codes -- and
        // disfiguring those breaks the page rather than testing it. The language switcher is the sharpest
        // case: mangle the property naming the languages on offer and the pseudo-locale becomes a page with
        // no way back out of it. Being in the catalog is what declares a property to be prose.
        return translated == null ? shipped : disfigure(translated, pseudo);
    }

    @Override
    public SortedMap<String, String> getAll(final String catalog, final Locale locale)
    {
        final PseudoLocale.Style pseudo = PseudoLocale.styleOf(locale);
        final ResourceBundle bundle = this.bundles.getResourceBundle(catalog, source(locale, pseudo));
        final SortedMap<String, String> messages = new TreeMap<>();
        if (bundle == null) {
            LOGGER.debug("No [{}] catalog for [{}]; answering with nothing", catalog, locale);
            return messages;
        }
        Collections.list(bundle.getKeys())
            .forEach(key -> messages.put(key, disfigure(bundle.getString(key), pseudo)));
        return messages;
    }

    @Override
    public String format(final String catalog, final String key, final Locale locale,
        final Map<String, Object> arguments)
    {
        final String pattern = get(catalog, key, locale);
        if (arguments.isEmpty()) {
            // Not merely an optimization: an un-parameterized message is ordinary prose, and running it through
            // a formatter would make an apostrophe — which ICU reads as its quoting character — silently
            // disappear from every language that uses one.
            return pattern;
        }
        return new MessageFormat(pattern, locale == null ? Locale.getDefault() : locale).format(arguments);
    }

    /**
     * Which language to actually read the catalog in.
     *
     * <p>A pseudo-locale is derived from the source language on the spot rather than stored. Derived, it
     * cannot drift from the catalog it is testing and cannot be partially written: every key is present, so
     * the fallback never fires and any plain text left on screen is provably text that never went through a
     * catalog at all.</p>
     *
     * @param requested the language asked for
     * @param pseudo which pseudo-locale it names, or {@code null} for an ordinary language
     * @return the language to look the message up in
     */
    private static Locale source(final Locale requested, final PseudoLocale.Style pseudo)
    {
        return pseudo == null ? requested : Locale.ENGLISH;
    }

    private static String disfigure(final String message, final PseudoLocale.Style pseudo)
    {
        return pseudo == null ? message : PseudoLocale.transform(message, pseudo);
    }

    /**
     * One message, or nothing where the catalog does not have it.
     *
     * @param catalog which catalog to look in
     * @param key the message key
     * @param locale the language to look it up in
     * @return the message as written, or {@code null} where there is none
     */
    private String lookUp(final String catalog, final String key, final Locale locale)
    {
        final ResourceBundle bundle = this.bundles.getResourceBundle(catalog, locale);
        if (bundle == null) {
            LOGGER.debug("No [{}] catalog for [{}]", catalog, locale);
            return null;
        }
        try {
            final String message = bundle.getString(key);
            // Sling's own bundles answer a miss with the key itself rather than throwing, so catching the
            // exception is not enough on its own: without this check every untranslated property would be
            // replaced by its own repository path, which is how this first showed up on a rendered page.
            return message == null || message.equals(key) ? null : message;
        } catch (final MissingResourceException e) {
            // A bundle that does throw, which the specification allows and some implementations do
            return null;
        }
    }
}
