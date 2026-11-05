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
 * Gives Jakarta Activation the Angus Mail classes that write each part of an email. Without it, an email with an HTML
 * body or an attachment cannot be sent.
 *
 * <p>
 * Writing a part of a message asks the default {@link CommandMap} for a handler. The stock map reads handler class
 * names from {@code META-INF/mailcap} files and loads each by name. Inside OSGi the loading fails: neither the thread
 * context classloader nor the Jakarta Activation bundle can see the Angus Mail bundle. This map names the handlers in
 * code instead, and the bundle imports their package.
 * </p>
 *
 * <p>
 * A plain text message is sent even without a handler, because Jakarta Activation writes a {@code String} body
 * itself. Only a multipart body needs one, which is any email with an HTML body or an attachment.
 * </p>
 *
 * <p>
 * The handlers are those of Angus Mail's own {@code META-INF/mailcap}. It comments out its two image handlers,
 * since {@code java.awt.Toolkit} does not work on a server. An attachment is written from its data source and needs
 * no handler.
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
     * Offers no commands, only handlers.
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
     * The handler that writes a media type.
     *
     * @param mimeType the media type of the part being written, with any parameters, such as
     *            {@code text/plain; charset=UTF-8}
     * @return a new handler, or {@code null} if Angus Mail has none for the type
     */
    @Override
    public DataContentHandler createDataContentHandler(final String mimeType)
    {
        // The type without its parameters, since a multipart type always carries a boundary
        final String type = mimeType == null ? "" : mimeType.toLowerCase().split(";")[0].trim();
        if (type.startsWith("multipart/")) {
            // One handler writes every multipart subtype, alternative and related included
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
     * Installs this map as the JVM-wide default command map when the bundle starts.
     */
    @Activate
    public void activate()
    {
        CommandMap.setDefaultCommandMap(this);
        LOGGER.debug("Installed the Angus content handlers as the default command map");
    }

    /**
     * Restores the stock default command map, unless another map has replaced this one since.
     */
    @Deactivate
    public void deactivate()
    {
        if (CommandMap.getDefaultCommandMap() == this) {
            CommandMap.setDefaultCommandMap(null);
        }
    }
}
