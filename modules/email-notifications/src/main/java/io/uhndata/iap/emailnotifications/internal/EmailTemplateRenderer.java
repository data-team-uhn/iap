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
 * Turns an email template into the text that gets sent, with Apache Velocity.
 *
 * <p>
 * <strong>Why a template engine at all.</strong> Placeholder substitution can only put a value where a name is. An
 * email that has something to say about a submission needs to say different things depending on what happened to it,
 * list however many answers or reviewers there are, and leave out a paragraph that does not apply -- none of which is
 * substitution. Doing it in Java instead would move the wording into code, and the whole point of keeping templates in
 * the repository is that a deployment can reword what the platform says without a rebuild.
 * </p>
 *
 * <p>
 * <strong>Templates are content, so the engine is sandboxed.</strong> A template is editable by a deployment and the
 * values in it are somebody's answers, so the configuration below is as much about what a template <em>cannot</em> do
 * as what it can:
 * </p>
 * <ul>
 * <li><strong>No resource loader but the in-memory one</strong>, so <code>#include</code> and <code>#parse</code> can
 * reach nothing on the filesystem or the classpath.</li>
 * <li><strong>{@code SecureUberspector}</strong>, so a template cannot reflect its way out of the values it was given:
 * it permits <code>$x.getClass().getName()</code> and stops at the classloader, the method list and
 * <code>forName</code>.</li>
 * <li><strong>Strict references</strong>, so a name that was never supplied fails the whole render instead of mailing
 * somebody the literal <code>$name</code>.</li>
 * </ul>
 *
 * <p>
 * Strictness leaves two ways to write something optional, and they mean different things. <code>#if($name)</code>
 * covers a name that may not have been supplied at all. <code>$!{name}</code> covers one that was supplied as
 * nothing -- and only that: a quiet reference to a name nobody supplied is still refused, because a template asking
 * for something the caller has never heard of is a bug either way round. All four corners are pinned by tests.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class EmailTemplateRenderer
{
    /**
     * The one renderer. A {@link VelocityEngine} is expensive to configure and thread-safe once initialized, which is
     * the shape of a singleton rather than of something built per email.
     */
    private static final EmailTemplateRenderer INSTANCE = new EmailTemplateRenderer();

    /** What Velocity calls this when it reports where in a template something went wrong. */
    private static final String LOG_TAG = "email template";

    private final VelocityEngine velocity;

    /**
     * Escapes every reference it is shown. Stateless, so the one instance is attached to as many contexts as there
     * are HTML bodies being rendered.
     */
    private final EventCartridge htmlEscaping;

    /** Configures the engine. Private: there is no reason to have a second one, since rendering keeps no state. */
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
     * Escapes every value on its way into an HTML body.
     *
     * <p>
     * Written here rather than taken from Velocity's own {@code EscapeHtmlReference}, which is deprecated in 2.4
     * along with the rest of that family -- they were built on a {@code StringEscapeUtils} that has since left
     * commons-lang3. The interface behind them is three lines, so there is nothing to replace but the escaping
     * itself.
     * </p>
     *
     * <p>
     * <strong>XML escaping rather than HTML entity escaping</strong>, which is the same five characters that could
     * break markup -- {@code & < > " '} -- without turning every accented letter into an entity. An email is UTF-8;
     * a recipient named Zoë should be greeted by name and not by {@code Zo&euml;}.
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
            // Left alone rather than turned into "null": a reference resolving to nothing writes nothing
            return value == null ? null : StringEscapeUtils.escapeXml11(String.valueOf(value));
        }
    }

    /**
     * The renderer everything shares.
     *
     * @return the one configured engine
     */
    @NotNull
    public static EmailTemplateRenderer get()
    {
        return INSTANCE;
    }

    /**
     * Fills in one template.
     *
     * @param template the template text, may be {@code null} when the email has no such part
     * @param values the values it may refer to, by name
     * @param escapeHtml whether every value should be HTML-escaped on its way in, which is what an HTML body wants
     *            and a plain text body must not have
     * @return the rendered text, {@code null} if there was no template to render
     * @throws EmailTemplateException if the template does not parse, or refers to something not among the values
     */
    @Nullable
    public String render(@Nullable final String template, @NotNull final Map<String, ?> values,
        final boolean escapeHtml)
    {
        if (template == null) {
            return null;
        }
        // A COPY of the caller's values: a VelocityContext writes through to the map it is handed -- #foreach puts
        // its loop variable there -- so an immutable Map.of() fails the render outright, and a mutable one silently
        // comes back with the loop leftovers in it
        final VelocityContext context = new VelocityContext(new HashMap<>(values));
        if (escapeHtml) {
            this.htmlEscaping.attachToContext(context);
        }
        final StringWriter rendered = new StringWriter();
        try {
            this.velocity.evaluate(context, rendered, LOG_TAG, template);
        } catch (final RuntimeException e) {
            // Velocity's own message names the line and column, which is the one thing a template author needs and
            // the one thing this class knows that the caller does not
            throw new EmailTemplateException("The email template could not be rendered: " + e.getMessage(), e);
        }
        return rendered.toString();
    }
}
