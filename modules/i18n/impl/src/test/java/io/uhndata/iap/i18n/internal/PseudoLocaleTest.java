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

import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.icu.text.MessageFormat;
import io.uhndata.iap.i18n.internal.PseudoLocale.Style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PseudoLocale}.
 *
 * <p>The oracle throughout is the pattern's skeleton: a transform that damages syntax usually still produces
 * something that parses, only into a different shape, so "it still parses" would pass for the wrong reason.
 * Every case here asserts the structure is untouched and the readable text is not.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
class PseudoLocaleTest
{
    private static final String PLURAL =
        "{count, plural, one {# request is waiting} other {# requests are waiting}}";

    private static final String SELECT =
        "{gender, select, female {She approved it} male {He approved it} other {They approved it}}";

    /** The text as written, with the direction override taken back off. */
    private static String unmirrored(final String text)
    {
        return text.replace("\u202e", "").replace("\u202c", "");
    }

    @Test
    void namesNoPseudoLocaleForNothing()
    {
        assertNull(PseudoLocale.styleOf(null));
        assertNull(PseudoLocale.styleOf(Locale.FRENCH));
        assertNull(PseudoLocale.styleOf(Locale.CANADA));
        assertEquals(PseudoLocale.Style.ACCENTED, PseudoLocale.styleOf(Locale.forLanguageTag("en-XA")));
        assertEquals(PseudoLocale.Style.SHORTENED, PseudoLocale.styleOf(Locale.forLanguageTag("en-XB")));
    }

    @Test
    void accentsAndLengthensOrdinaryProse()
    {
        final String result = PseudoLocale.transform("Sign in", Style.ACCENTED);

        assertTrue(result.startsWith("[") && result.endsWith("]"), result);
        assertFalse(result.contains("Sign in"), result);
        // Longer than the original, which is the point: a layout that only fits English is a bug
        assertTrue(result.length() > "Sign in".length() + 2, result);
    }

    @Test
    void shortensOrdinaryProseWithoutMarkingIt()
    {
        final String result = PseudoLocale.transform("Sign in to continue", Style.SHORTENED);

        // Unmarked on purpose: brackets would add back the width this exists to take away
        assertFalse(result.startsWith("["), result);
        assertEquals("Sgn n t cntn", unmirrored(result));
    }

    @Test
    void leavesMarkdownDelimitersWhereTheyWere()
    {
        // What cutting the text short got wrong, and the reason it is gone. Shipped text is Markdown, and a
        // measured fraction of its length lands mid-token as often as not: "**faster**" came back as
        // "**faster*", and one unbalanced delimiter changes how everything after it is parsed. Dropping
        // letters cannot reach a character that is not one.
        final String result = PseudoLocale.transform("Designed to make the process **faster**", Style.SHORTENED);

        assertEquals("Dsgnd t mk th prcss **fstr**", unmirrored(result));
    }

    @Test
    void leavesAWordThatIsNothingButVowelsBehind()
    {
        // Removing every letter of a word would run its neighbours together into a different sentence, which
        // is a change of meaning rather than of length
        assertEquals("Sbmt a d", unmirrored(PseudoLocale.transform("Submit a idea", Style.SHORTENED)));
    }

    @Test
    void overridesEachLineInItsOwnRight()
    {
        // A direction override reaches the end of its bidi paragraph and no further, and a forced line break
        // ends one. With a single override around the whole message only the first line turned around, and
        // the rest read left to right -- a layout half-checked, with the unchecked half invisible.
        final String result = PseudoLocale.transform("First line\nSecond line", Style.SHORTENED);

        for (final String line : result.split("\n")) {
            assertTrue(line.startsWith("\u202e"), line);
            assertTrue(line.endsWith("\u202c"), line);
        }
    }

    @Test
    void leavesAPluralsKeywordsAndCategoriesAlone()
    {
        // The failure this class was written twice to avoid: `plural` and the category names `one`/`other`
        // sit outside any braces, so a transform that works on brace depth accents them into `plúrál` and
        // the pattern stops parsing.
        final String result = PseudoLocale.transform(PLURAL, Style.ACCENTED);

        assertTrue(result.contains("plural"), result);
        assertTrue(result.contains("one {"), result);
        assertTrue(result.contains("other {"), result);
        assertEquals(PseudoLocale.skeleton(PLURAL), PseudoLocale.skeleton(result));
    }

    @Test
    void leavesASelectsKeywordsAndCategoriesAlone()
    {
        final String result = PseudoLocale.transform(SELECT, Style.ACCENTED);

        assertTrue(result.contains("select"), result);
        assertTrue(result.contains("female {"), result);
        assertEquals(PseudoLocale.skeleton(SELECT), PseudoLocale.skeleton(result));
    }

    @Test
    void leavesArgumentNamesAlone()
    {
        final String result = PseudoLocale.transform("Good morning, {name}", Style.ACCENTED);

        // Rewriting this would not break the syntax, it would break the substitution — quieter and worse
        assertTrue(result.contains("{name}"), result);
        assertEquals(PseudoLocale.skeleton("Good morning, {name}"), PseudoLocale.skeleton(result));
    }

    @Test
    void stillFormatsAfterBeingDisfigured()
    {
        // The end-to-end proof: ICU can still read it, still picks the right plural category, and still
        // substitutes the number.
        final String result = PseudoLocale.transform(PLURAL, Style.ACCENTED);

        final String formatted = new MessageFormat(result, Locale.ENGLISH).format(Map.of("count", 1));

        assertTrue(formatted.startsWith("["), formatted);
        assertTrue(formatted.contains("1"), formatted);
    }

    @Test
    void keepsTheStructureOfNestedArguments()
    {
        final String nested = "{count, plural, one {{name} has one request} other {{name} has # requests}}";

        final String result = PseudoLocale.transform(nested, Style.ACCENTED);

        assertTrue(result.contains("{name}"), result);
        assertEquals(PseudoLocale.skeleton(nested), PseudoLocale.skeleton(result));
    }

    @Test
    void leavesQuotingAlone()
    {
        // The apostrophe is MessageFormat's escape character: rewriting one changes what follows it
        final String quoted = "It''s waiting on '{someone}'";

        final String result = PseudoLocale.transform(quoted, Style.ACCENTED);

        assertEquals(PseudoLocale.skeleton(quoted), PseudoLocale.skeleton(result));
        assertTrue(result.contains("'"), result);
    }

    @Test
    void shortenedFormAlsoKeepsTheStructure()
    {
        final String result = PseudoLocale.transform(PLURAL, Style.SHORTENED);

        assertEquals(PseudoLocale.skeleton(PLURAL), PseudoLocale.skeleton(result));
        assertTrue(result.contains("plural"), result);
    }

    @Test
    void doesNotSwallowTheSpaceBetweenTwoArguments()
    {
        // Shortening a run that is only whitespace would glue the two values together, which reads as a
        // different sentence rather than a shorter one.
        final String result = PseudoLocale.transform("{first} {second}", Style.SHORTENED);

        assertEquals("{first} {second}", unmirrored(result));
    }

    @Test
    void turnsTheShortenedFormAround()
    {
        // Hebrew and Arabic are both more compact than English and both read the other way, so one
        // pseudo-locale meeting a layout with both is closer to a real language than two separate checks
        final String result = PseudoLocale.transform("Sign in", Style.SHORTENED);

        assertTrue(result.startsWith("\u202e"), result);
        assertTrue(result.endsWith("\u202c"), result);
    }

    @Test
    void leavesTheWordsThemselvesTheRightWayRound()
    {
        // Wrapped in an override rather than reversed, so a test still finds the text where it expects it, a
        // screen reader still reads it, and it can still be copied — only the rendering turns around
        final String result = PseudoLocale.transform("Sign in", Style.SHORTENED);

        assertFalse(unmirrored(result).contains("ni ngiS"), result);
        assertTrue(inWrittenOrder(unmirrored(result), "Sign in"), result);
    }

    /**
     * Whether every character that survived is still in the order it was written in.
     *
     * <p>What "not reversed" actually means, now that the shortened form drops letters from the middle
     * rather than cutting the end off: the surviving letters are a subsequence of the original, never a
     * rearrangement of it.</p>
     *
     * @param kept the disfigured text
     * @param original the text it was made from
     * @return {@code true} where nothing has moved
     */
    private static boolean inWrittenOrder(final String kept, final String original)
    {
        int index = 0;
        for (final char c : kept.toCharArray()) {
            index = original.indexOf(c, index);
            if (index < 0) {
                return false;
            }
            index++;
        }
        return true;
    }

    @Test
    void doesNotTurnTheRestOfThePageAround()
    {
        // An override that is never popped runs on into whatever follows it on the page
        final String result = PseudoLocale.transform("Sign in", Style.SHORTENED);

        assertEquals(1, result.chars().filter(c -> c == 0x202E).count(), result);
        assertEquals(1, result.chars().filter(c -> c == 0x202C).count(), result);
    }

    @Test
    void leavesTheLongFormTheWayItReads()
    {
        // Only the short one mirrors; bracketing and turning around at once would tell you less about each
        final String result = PseudoLocale.transform("Sign in", Style.ACCENTED);

        assertFalse(result.contains("\u202e"), result);
    }

    @Test
    void handlesAMessageThatIsNothingButAnArgument()
    {
        final String result = PseudoLocale.transform("{name}", Style.ACCENTED);

        assertEquals("[{name}]", result);
    }

    @Test
    void disfiguresTextThatIsNotAPatternAtAll()
    {
        // Configured content is ordinary prose, not an ICU pattern, and a stray brace in a sentence somebody
        // typed is a typo rather than a reason to fail the page it is on.
        final String result = PseudoLocale.transform("Ask for {an approval", Style.ACCENTED);

        assertTrue(result.startsWith("[") && result.endsWith("]"), result);
        assertFalse(result.contains("approval"), result);
    }

    @Test
    void turnsAroundTextThatIsNotAPatternAtAll()
    {
        final String result = PseudoLocale.transform("Ask for {an approval", Style.SHORTENED);

        assertTrue(result.startsWith("\u202e") && result.endsWith("\u202c"), result);
    }

    @Test
    void hasNoInstances() throws Exception
    {
        final Constructor<PseudoLocale> constructor = PseudoLocale.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
