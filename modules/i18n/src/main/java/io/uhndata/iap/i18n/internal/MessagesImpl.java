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

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

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
        final ResourceBundle bundle = this.bundles.getResourceBundle(catalog, locale);
        if (bundle == null) {
            LOGGER.debug("No [{}] catalog for [{}]; answering with the key", catalog, locale);
            return key;
        }
        try {
            return bundle.getString(key);
        } catch (final MissingResourceException e) {
            // Answering with the key is what makes a missing message obvious on screen without being fatal.
            // The build's key check is what stops one reaching a release; this is only the runtime behavior.
            LOGGER.debug("No message [{}] in [{}] for [{}]", key, catalog, locale);
            return key;
        }
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
}
