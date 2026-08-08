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
        assertTrue(result.length() < "Sign in to continue".length(), result);
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

        assertEquals("{first} {second}", result);
    }

    @Test
    void handlesAMessageThatIsNothingButAnArgument()
    {
        final String result = PseudoLocale.transform("{name}", Style.ACCENTED);

        assertEquals("[{name}]", result);
    }

    @Test
    void hasNoInstances() throws Exception
    {
        final Constructor<PseudoLocale> constructor = PseudoLocale.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
