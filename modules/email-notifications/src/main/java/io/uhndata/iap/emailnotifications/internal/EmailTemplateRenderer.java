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

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.app.event.ReferenceInsertionEventHandler;
import org.apache.velocity.context.Context;
import org.apache.velocity.runtime.RuntimeConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.emailnotifications.api.EmailTemplateException;

/**
 * Fills in the subject and bodies of an email template with Apache Velocity.
 *
 * <p>
 * <strong>The engine is sandboxed</strong>, since a deployment can edit the templates:
 * </p>
 * <ul>
 * <li><strong>The only resource loader is an empty in-memory one</strong>, so {@code #include} and {@code #parse}
 * reach nothing on the filesystem or the classpath.</li>
 * <li><strong>{@code SecureUberspector}</strong> stops a template from reflecting beyond its values. It allows
 * {@code $x.getClass().getName()}, and refuses the classloader, the method list and {@code forName}.</li>
 * <li><strong>References are strict</strong>, so a variable that nobody supplied fails the whole template instead of
 * mailing somebody the literal {@code $name}.</li>
 * </ul>
 *
 * <p>
 * A template has two ways to write something optional. {@code #if($name)} is for a variable that may not be supplied
 * at all. {@code $!{name}} is for one supplied as {@code null}, and still fails for a variable nobody supplied.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class EmailTemplateRenderer
{
    /** The one renderer. A {@link VelocityEngine} is thread-safe once initialized, so every email shares it. */
    private static final EmailTemplateRenderer INSTANCE = new EmailTemplateRenderer();

    /** The template name in Velocity's error messages, before the line and column. */
    private static final String LOG_TAG = "email template";

    private final VelocityEngine velocity;

    /** Escapes every value put in an HTML body. It keeps no state, so every HTML body shares it. */
    private final EventCartridge htmlEscaping;

    /** Configures the engine. */
    private EmailTemplateRenderer()
    {
        this.velocity = new VelocityEngine();
        this.velocity.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        this.velocity.setProperty("resource.loader.string.class",
            "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        this.velocity.setProperty(RuntimeConstants.UBERSPECT_CLASSNAME,
            "org.apache.velocity.util.introspection.SecureUberspector");
        this.velocity.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "true");
        this.velocity.setProperty(RuntimeConstants.RUNTIME_LOG_NAME, EmailTemplateRenderer.class.getName());
        this.velocity.init();
        this.htmlEscaping = new EventCartridge();
        this.htmlEscaping.addEventHandler(new EscapeForMarkup());
    }

    /**
     * Escapes every value put in an HTML body. It is written here because Velocity's own
     * {@code EscapeHtmlReference} is deprecated in 2.4.
     *
     * <p>
     * <strong>The escaping is XML escaping, not HTML escaping.</strong> It escapes the characters that break markup,
     * {@code & < > " '}, and leaves accented letters alone: Zoë stays Zoë, not {@code Zo&euml;}.
     * </p>
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class EscapeForMarkup implements ReferenceInsertionEventHandler
    {
        @Override
        public Object referenceInsert(final Context context, final String reference, final Object value)
        {
            // String.valueOf would write "null"
            return value == null ? null : StringEscapeUtils.escapeXml11(String.valueOf(value));
        }
    }

    /**
     * The renderer every email shares.
     *
     * @return the one renderer
     */
    @NotNull
    public static EmailTemplateRenderer get()
    {
        return INSTANCE;
    }

    /**
     * Fills in one template.
     *
     * @param template the Velocity template text, or {@code null} for an email with no such part
     * @param values the values the template may use, by variable name; the map is not changed
     * @param escapeHtml {@code true} to escape each value for an HTML body, {@code false} for a subject or a plain
     *            text body
     * @return the filled in text, or {@code null} if the template is {@code null}
     * @throws EmailTemplateException if the template does not parse, or uses a variable that is not among the values
     */
    @Nullable
    public String render(@Nullable final String template, @NotNull final Map<String, ?> values,
        final boolean escapeHtml)
    {
        if (template == null) {
            return null;
        }
        // A copy, since a VelocityContext keeps its variables in the map it is given, and #set and #foreach write
        // there. An immutable Map.of() would fail the template.
        final VelocityContext context = new VelocityContext(new HashMap<>(values));
        if (escapeHtml) {
            this.htmlEscaping.attachToContext(context);
        }
        final StringWriter rendered = new StringWriter();
        try {
            this.velocity.evaluate(context, rendered, LOG_TAG, template);
        } catch (final RuntimeException e) {
            throw new EmailTemplateException("The email template could not be rendered: " + e.getMessage(), e);
        }
        return rendered.toString();
    }
}
