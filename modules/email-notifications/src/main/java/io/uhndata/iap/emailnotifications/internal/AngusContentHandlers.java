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
package io.uhndata.iap.emailnotifications.internal;

import jakarta.activation.CommandInfo;
import jakarta.activation.CommandMap;
import jakarta.activation.DataContentHandler;

import org.eclipse.angus.mail.handlers.message_rfc822;
import org.eclipse.angus.mail.handlers.multipart_mixed;
import org.eclipse.angus.mail.handlers.text_html;
import org.eclipse.angus.mail.handlers.text_plain;
import org.eclipse.angus.mail.handlers.text_xml;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names the class that writes each part of an email, instead of letting Jakarta Activation look it up.
 *
 * <p>
 * Writing any part of a message asks the {@link CommandMap} for a handler, and the default map answers by reading
 * {@code META-INF/mailcap} files for handler <em>class names</em>, then resolving each name with the thread context
 * classloader. Inside OSGi neither half works: the sending thread's context classloader belongs to whoever is
 * sending, the map's own belongs to the activation API bundle, and neither can see the bundle the handlers live in.
 * </p>
 *
 * <p>
 * That failure hides well. Every bundle involved reports itself {@code Active}, because each of the OSGi
 * requirements behind the lookup is declared optional, and a plain text message goes out regardless: with no
 * handler at all, a {@code String} body is written by a fallback that special-cases {@code String} and
 * {@code byte[]}. Only a {@code Multipart} body needs a real handler, so every template with an HTML alternative or
 * an inline attachment fails while the plain text smoke test passes.
 * </p>
 *
 * <p>
 * Naming the classes here removes the lookup rather than repairing it. This bundle imports
 * {@code org.eclipse.angus.mail.handlers} like any other package, so the framework wires it and the compiler checks
 * it: no file to find, no name to resolve, nothing for a classloader to get wrong. That is also why the runtime
 * needs neither a {@code ServiceLoader} mediator nor a fragment attached to the activation API bundle &mdash; both
 * existed only to make that lookup succeed.
 * </p>
 *
 * <p>
 * This is the shape of Sling's own answer to the same problem one API generation earlier:
 * {@code org.apache.sling.javax.activation}, which the boot feature still installs, registers an
 * {@code OsgiMailcapCommandMap} as the default from its activator. There is no equivalent for the {@code jakarta}
 * namespace, and this is the small version of one: a single provider means a fixed list rather than a map that
 * tracks bundles.
 * </p>
 *
 * <p>
 * The set below is Angus Mail's own {@code META-INF/mailcap}, entry for entry. Its two image handlers are left out
 * because upstream leaves them out too, commented with the reason: {@code java.awt.Toolkit} does not work on a
 * server. Attachments are unaffected either way, since those are written straight from a byte-array data source and
 * never consult this map.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = {})
public class AngusContentHandlers extends CommandMap
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AngusContentHandlers.class);

    /**
     * Offers no commands, only handlers. {@code CommandMap} carries both because it dates from an era of desktop
     * "open with" verbs, and nothing sending mail asks for those.
     *
     * @param mimeType the media type being asked about
     * @return an empty array, always
     */
    @Override
    public CommandInfo[] getPreferredCommands(final String mimeType)
    {
        return new CommandInfo[0];
    }

    /**
     * Offers no commands, only handlers.
     *
     * @param mimeType the media type being asked about
     * @return an empty array, always
     */
    @Override
    public CommandInfo[] getAllCommands(final String mimeType)
    {
        return new CommandInfo[0];
    }

    /**
     * Offers no commands, only handlers.
     *
     * @param mimeType the media type being asked about
     * @param commandName the command being asked for
     * @return {@code null}, always
     */
    @Override
    public CommandInfo getCommand(final String mimeType, final String commandName)
    {
        return null;
    }

    /**
     * The handler that writes this media type, or {@code null} when none of Angus' handlers does, which the caller
     * reports as an unsupported type just as an unmatched mailcap entry would.
     *
     * @param mimeType the media type of the part being written, possibly followed by parameters
     * @return a handler, or {@code null} if this type is not one Angus provides a handler for
     */
    @Override
    public DataContentHandler createDataContentHandler(final String mimeType)
    {
        // Match on the bare type: a real part carries parameters too, and a multipart always carries the boundary
        // separating its own children
        final String type = mimeType == null ? "" : mimeType.toLowerCase().split(";")[0].trim();
        if (type.startsWith("multipart/")) {
            // One handler for every multipart subtype, alternative and related included, exactly as upstream's
            // `multipart/*` fallback entry does: what differs between them is how a reader should present the
            // parts, not how they are written out
            return new multipart_mixed();
        }
        return switch (type) {
            case "text/plain" -> new text_plain();
            case "text/html" -> new text_html();
            case "text/xml" -> new text_xml();
            case "message/rfc822" -> new message_rfc822();
            case null, default -> null;
        };
    }

    /**
     * Installs this map as the JVM-wide default, replacing the one that cannot find its handlers. Immediate, so that
     * it happens when the bundle starts rather than when something first asks to send.
     */
    @Activate
    public void activate()
    {
        CommandMap.setDefaultCommandMap(this);
        LOGGER.debug("Installed the Angus content handlers as the default command map");
    }

    /**
     * Restores the stock default, so that a message sent after this bundle stops fails on its own terms rather than
     * against classes that are no longer wired.
     */
    @Deactivate
    public void deactivate()
    {
        if (CommandMap.getDefaultCommandMap() == this) {
            CommandMap.setDefaultCommandMap(null);
        }
    }
}
