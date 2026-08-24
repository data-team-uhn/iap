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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jakarta.activation.CommandMap;
import jakarta.activation.DataContentHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.eclipse.angus.mail.handlers.message_rfc822;
import org.eclipse.angus.mail.handlers.multipart_mixed;
import org.eclipse.angus.mail.handlers.text_html;
import org.eclipse.angus.mail.handlers.text_plain;
import org.eclipse.angus.mail.handlers.text_xml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AngusContentHandlers}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class AngusContentHandlersTest
{
    private final AngusContentHandlers handlers = new AngusContentHandlers();

    @AfterEach
    void restoreTheDefaultCommandMap()
    {
        // The command map is JVM-wide state, so a test that installs one has to put it back or the next test
        // in this fork inherits it
        CommandMap.setDefaultCommandMap(null);
    }

    @Test
    void eachMediaTypeGetsTheHandlerThatWritesIt()
    {
        assertInstanceOf(text_plain.class, this.handlers.createDataContentHandler("text/plain"));
        assertInstanceOf(text_html.class, this.handlers.createDataContentHandler("text/html"));
        assertInstanceOf(text_xml.class, this.handlers.createDataContentHandler("text/xml"));
        assertInstanceOf(message_rfc822.class, this.handlers.createDataContentHandler("message/rfc822"));
    }

    @Test
    void everySubtypeOfMultipartIsWrittenTheSameWay()
    {
        assertInstanceOf(multipart_mixed.class, this.handlers.createDataContentHandler("multipart/mixed"));
        assertInstanceOf(multipart_mixed.class, this.handlers.createDataContentHandler("multipart/alternative"));
        assertInstanceOf(multipart_mixed.class, this.handlers.createDataContentHandler("multipart/related"));
    }

    @Test
    void theTypeIsFoundPastItsParametersAndItsCase()
    {
        assertInstanceOf(text_plain.class, this.handlers.createDataContentHandler("Text/Plain; charset=UTF-8"));
        assertInstanceOf(multipart_mixed.class,
            this.handlers.createDataContentHandler("MULTIPART/mixed; boundary=\"----=_Part_0\""));
    }

    @Test
    void anUnhandledTypeIsDeclinedRatherThanGuessedAt()
    {
        assertNull(this.handlers.createDataContentHandler("application/pdf"));
        assertNull(this.handlers.createDataContentHandler(""));
        assertNull(this.handlers.createDataContentHandler(null));
    }

    @Test
    void noCommandsAreOffered()
    {
        assertEquals(0, this.handlers.getPreferredCommands("text/plain").length);
        assertEquals(0, this.handlers.getAllCommands("text/plain").length);
        assertNull(this.handlers.getCommand("text/plain", "view"));
    }

    @Test
    void activatingInstallsThisMapAndDeactivatingStandsDown()
    {
        this.handlers.activate();
        assertSame(this.handlers, CommandMap.getDefaultCommandMap());

        this.handlers.deactivate();
        assertFalse(CommandMap.getDefaultCommandMap() instanceof AngusContentHandlers);
    }

    @Test
    void standingDownLeavesSomebodyElsesMapAlone()
    {
        final CommandMap other = new AngusContentHandlers();
        CommandMap.setDefaultCommandMap(other);

        this.handlers.deactivate();

        assertSame(other, CommandMap.getDefaultCommandMap());
    }

    /**
     * The handlers exist to write messages, so this writes one of the shape this module actually sends: a
     * multipart body with a plain text alternative and an HTML one. Three of the five handlers have to answer
     * correctly for the bytes to come out right, which a lookup returning the wrong class would not manage.
     *
     * @throws Exception if building or writing the message fails
     */
    @Test
    void theHandlersWriteAMultipartMessage() throws Exception
    {
        this.handlers.activate();
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        final MimeMultipart body = new MimeMultipart("alternative");
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Your request was approved", "UTF-8");
        body.addBodyPart(text);
        final MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Your request was approved</p>", "text/html; charset=UTF-8");
        body.addBodyPart(html);
        message.setContent(body);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        final String written = out.toString(StandardCharsets.UTF_8);

        assertTrue(written.contains("multipart/alternative"), written);
        assertTrue(written.contains("Your request was approved"), written);
        assertTrue(written.contains("<p>Your request was approved</p>"), written);
    }

    /**
     * The list of handlers is maintained by hand, which is only safe while something notices when Angus changes
     * its own. This reads the mailcap file out of the Angus jar and asks the map for every type named in it, so a
     * handler added or renamed upstream fails the build instead of failing a send.
     *
     * @throws IOException if the mailcap file cannot be read
     */
    @Test
    void everyHandlerAngusDeclaresIsAccountedFor() throws IOException
    {
        final List<String> entries = readAngusMailcap();
        assertFalse(entries.isEmpty(), "Found no mailcap entries at all, so this test proved nothing");

        for (final String entry : entries) {
            final String mimeType = entry.substring(0, entry.indexOf(';')).trim();
            final String expected = handlerClassIn(entry);
            // `multipart/*` is a pattern rather than a type, so ask about a real member of it
            final String asked = mimeType.endsWith("/*") ? mimeType.replace("/*", "/mixed") : mimeType;

            final DataContentHandler handler = this.handlers.createDataContentHandler(asked);

            assertNotNull(handler, "No handler for " + asked + ", which Angus' mailcap names one for");
            assertEquals(expected, handler.getClass().getName(), "Wrong handler for " + asked);
        }
    }

    /**
     * The content-handler lines of every {@code META-INF/mailcap} on the classpath, commented-out ones excluded --
     * upstream comments out its image handlers deliberately, and this map leaves them out to match.
     *
     * @return one string per entry
     * @throws IOException if a mailcap file cannot be read
     */
    private static List<String> readAngusMailcap() throws IOException
    {
        final List<String> entries = new ArrayList<>();
        for (final URL url : Collections.list(
            AngusContentHandlersTest.class.getClassLoader().getResources("META-INF/mailcap"))) {
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> line.contains("x-java-content-handler="))
                    .forEach(entries::add);
            }
        }
        return entries;
    }

    /**
     * The class named by one mailcap entry.
     *
     * @param entry a single content-handler line
     * @return the fully qualified class name it names
     */
    private static String handlerClassIn(final String entry)
    {
        final String key = "x-java-content-handler=";
        final String tail = entry.substring(entry.indexOf(key) + key.length());
        final int end = tail.indexOf(';');
        final String name = (end < 0 ? tail : tail.substring(0, end)).trim();
        assertTrue(name.startsWith("org.eclipse.angus."), "Unexpected handler outside Angus: " + name);
        return name;
    }
}
