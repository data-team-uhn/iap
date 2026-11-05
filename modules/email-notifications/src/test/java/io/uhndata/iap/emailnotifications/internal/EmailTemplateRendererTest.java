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
        assertSame(EmailTemplateRenderer.get(), EmailTemplateRenderer.get());
    }

    @Test
    void anAbsentTemplateRendersToNothing()
    {
        assertNull(text(null, Map.of()));
    }

    @Test
    void putsAValueWhereANameIs()
    {
        assertEquals("Dear Alice, hello", text("Dear ${name}, hello", Map.of("name", "Alice")));
        assertEquals("Dear Alice", text("Dear $name", Map.of("name", "Alice")));
    }

    @Test
    void reachesIntoAValue()
    {
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
        final Map<String, Object> immutable = Map.of("answers", List.of("yes", "no"));
        assertEquals("[yes][no]", text("#foreach($a in $answers)[$a]#end", immutable));

        final Map<String, Object> mutable = new HashMap<>(immutable);
        text("#foreach($a in $answers)[$a]#end", mutable);
        assertEquals(Map.of("answers", List.of("yes", "no")), mutable);
    }

    @Test
    void escapesValuesInAnHtmlBody()
    {
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
        assertEquals("<p><strong>Alice</strong></p>",
            html("<p><strong>$name</strong></p>", Map.of("name", "Alice")));
    }

    @Test
    void doesNotEscapeAPlainTextBody()
    {
        assertEquals("A <b>bold</b> & brave proposal",
            text("$title", Map.of("title", "A <b>bold</b> & brave proposal")));
    }

    @Test
    void leavesAnAccentedNameAlone()
    {
        assertEquals("Dear Zoë", html("Dear $name", Map.of("name", "Zoë")));
    }

    @Test
    void refusesANameNobodySupplied()
    {
        final EmailTemplateException failure =
            assertThrows(EmailTemplateException.class, () -> text("Dear ${name}", Map.of()));

        assertTrue(failure.getMessage().contains("line 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("name"), failure.getMessage());
    }

    @Test
    void testsForSomethingGenuinelyOptional()
    {
        // A quiet reference does not make a variable that nobody supplied optional
        assertEquals("Hello.", text("#if($note)Note: $note#{else}Hello.#end", Map.of()));
        assertThrows(EmailTemplateException.class, () -> text("Dear $!{name}", Map.of()));
    }

    @Test
    void aValueSuppliedAsNothingRendersAsNothing()
    {
        // The HTML escaping writes nothing for it too
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
        assertThrows(EmailTemplateException.class, () -> text("#include('/etc/passwd')", Map.of()));
        assertThrows(EmailTemplateException.class, () -> text("#parse('/etc/passwd')", Map.of()));
    }

    @Test
    void cannotReflectItsWayOutOfTheValues()
    {
        final Map<String, Object> values = Map.of("name", "Alice");

        assertEquals("java.lang.String", text("$name.getClass().getName()", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.getClass().getClassLoader()", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.class.classLoader", values));
        assertThrows(EmailTemplateException.class, () -> text("$name.getClass().getMethods()", values));
        assertThrows(EmailTemplateException.class,
            () -> text("$name.getClass().forName('java.lang.Runtime')", values));
    }
}
