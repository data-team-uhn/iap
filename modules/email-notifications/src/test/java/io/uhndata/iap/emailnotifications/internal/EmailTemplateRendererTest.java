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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.emailnotifications.api.EmailTemplateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EmailTemplateRenderer}: what an email template can express, and what it must not be able to do.
 *
 * @version $Id$
 * @since 0.1.0
 */
class EmailTemplateRendererTest
{
    // The one everything shares: rendering keeps no state between calls, so there is nothing an instance of this
    // test's own would isolate
    private final EmailTemplateRenderer renderer = EmailTemplateRenderer.get();

    private String text(final String template, final Map<String, ?> values)
    {
        return this.renderer.render(template, values, false);
    }

    private String html(final String template, final Map<String, ?> values)
    {
        return this.renderer.render(template, values, true);
    }

    @Test
    void everythingSharesOneRenderer()
    {
        // Configuring an engine is expensive and it is thread-safe once initialized, so one is built and kept
        assertSame(EmailTemplateRenderer.get(), EmailTemplateRenderer.get());
    }

    @Test
    void anAbsentTemplateRendersToNothing()
    {
        // A template may legitimately have no HTML part, or no text part
        assertNull(text(null, Map.of()));
    }

    @Test
    void putsAValueWhereANameIs()
    {
        // The syntax that placeholder substitution already used, so no stored template had to be rewritten
        assertEquals("Dear Alice, hello", text("Dear ${name}, hello", Map.of("name", "Alice")));
        assertEquals("Dear Alice", text("Dear $name", Map.of("name", "Alice")));
    }

    @Test
    void reachesIntoAValue()
    {
        // The point of an engine over substitution: the caller passes a thing, not a pre-flattened string
        assertEquals("A proposal (approved)",
            text("$submission.title ($submission.status)",
                Map.of("submission", Map.of("title", "A proposal", "status", "approved"))));
    }

    @Test
    void leavesOutWhatDoesNotApply()
    {
        final String template = "#if($submission.status == 'rejected')Sorry.#{else}Congratulations.#end";

        assertEquals("Sorry.", text(template, Map.of("submission", Map.of("status", "rejected"))));
        assertEquals("Congratulations.", text(template, Map.of("submission", Map.of("status", "approved"))));
    }

    @Test
    void listsHoweverManyThereAre()
    {
        assertEquals("- yes\n- no\n",
            text("#foreach($answer in $answers)- $answer\n#end", Map.of("answers", List.of("yes", "no"))));
    }

    @Test
    void doesNotWriteBackIntoTheValuesItWasGiven()
    {
        // A VelocityContext writes through to the map it is handed, and #foreach puts its loop variable there. So an
        // immutable map fails the render outright, and a mutable one comes back with the loop leftovers in it --
        // both of which the caller would be entitled to find surprising, since it only asked for some text.
        final Map<String, Object> immutable = Map.of("answers", List.of("yes", "no"));
        assertEquals("[yes][no]", text("#foreach($a in $answers)[$a]#end", immutable));

        final Map<String, Object> mutable = new HashMap<>(immutable);
        text("#foreach($a in $answers)[$a]#end", mutable);
        assertEquals(Map.of("answers", List.of("yes", "no")), mutable);
    }

    @Test
    void escapesValuesInAnHtmlBody()
    {
        // Free text somebody typed, on its way into a part a mail client reads as markup
        assertEquals("<p>A &lt;b&gt;bold&lt;/b&gt; &amp; brave proposal</p>",
            html("<p>$title</p>", Map.of("title", "A <b>bold</b> & brave proposal")));
    }

    @Test
    void escapesWhatWouldEscapeAnAttributeToo()
    {
        assertEquals("<a title=\"He said &quot;no&quot;\">x</a>",
            html("<a title=\"$note\">x</a>", Map.of("note", "He said \"no\"")));
    }

    @Test
    void leavesTheTemplatesOwnMarkupAlone()
    {
        // Markup a template means to produce is written in the template, where it is not a value and not escaped
        assertEquals("<p><strong>Alice</strong></p>",
            html("<p><strong>$name</strong></p>", Map.of("name", "Alice")));
    }

    @Test
    void doesNotEscapeAPlainTextBody()
    {
        // The same value, in the part nothing renders as markup: entities there would be the bug
        assertEquals("A <b>bold</b> & brave proposal",
            text("$title", Map.of("title", "A <b>bold</b> & brave proposal")));
    }

    @Test
    void leavesAnAccentedNameAlone()
    {
        // XML escaping rather than HTML entity escaping: an email is UTF-8, and Zo&euml; is worse output
        assertEquals("Dear Zoë", html("Dear $name", Map.of("name", "Zoë")));
    }

    @Test
    void refusesANameNobodySupplied()
    {
        // Rendering it literally would mail somebody "Dear ${name}", which is worse than not sending
        final EmailTemplateException failure =
            assertThrows(EmailTemplateException.class, () -> text("Dear ${name}", Map.of()));

        // The position in the template is what a template author needs to fix it
        assertTrue(failure.getMessage().contains("line 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("name"), failure.getMessage());
    }

    @Test
    void testsForSomethingGenuinelyOptional()
    {
        // The way to write "if there is one": a quiet reference does NOT do it, because being unsupplied and being
        // supplied as nothing are different states and quiet references are only for the second
        assertEquals("Hello.", text("#if($note)Note: $note#{else}Hello.#end", Map.of()));
        assertThrows(EmailTemplateException.class, () -> text("Dear $!{name}", Map.of()));
    }

    @Test
    void aValueSuppliedAsNothingRendersAsNothing()
    {
        // Distinct from a name nobody supplied, which is refused above: the caller said there is no note, which is
        // an answer rather than an omission, and it has to survive being escaped on its way into an HTML body
        final Map<String, Object> values = new HashMap<>();
        values.put("note", null);

        assertEquals("Note: ", text("Note: $!note", values));
        assertEquals("<p>Note: </p>", html("<p>Note: $!note</p>", values));
    }

    @Test
    void refusesATemplateThatDoesNotParse()
    {
        assertThrows(EmailTemplateException.class, () -> text("#if($name", Map.of("name", "Alice")));
    }

    @Test
    void cannotReachTheFilesystem()
    {
        // Templates are content a deployment can edit, so the only resource loader is the in-memory one and nothing
        // was ever put in it
        assertThrows(EmailTemplateException.class, () -> text("#include('/etc/passwd')", Map.of()));
        assertThrows(EmailTemplateException.class, () -> text("#parse('/etc/passwd')", Map.of()));
    }

    @Test
    void cannotReflectItsWayOutOfTheValues()
    {
        final Map<String, Object> values = Map.of("name", "Alice");

        // SecureUberspector permits the class name and stops there, which is the line it draws
        assertEquals("java.lang.String", text("$name.getClass().getName()", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.getClass().getClassLoader()", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.class.classLoader", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.getClass().getMethods()", values));
        assertThrows(EmailTemplateException.class,
            () -> text("$name.getClass().forName('java.lang.Runtime')", values));
    }
}
