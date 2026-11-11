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

package io.uhndata.iap.utils;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SelectorUtils}.
 *
 * @version $Id$
 */
public class SelectorUtilsTest
{
    @Test
    public void testSimpleParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of("a", "b", "c", "d"), SelectorUtils.parseSelectors("a.b.c.d"));
    }

    @Test
    public void testEmptyStringParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseSelectors(""));
    }

    @Test
    public void testNullStringParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseSelectors(null));
    }

    @Test
    public void testEmptySelectorsAreIgnored()
        throws Exception
    {
        Assertions.assertEquals(List.of("a", "b", "c", "d"), SelectorUtils.parseSelectors("..a.b.c...d.."));
    }

    @Test
    public void testEveryDotInThePathSeparatesSelectors()
    {
        Assertions.assertEquals(List.of("a\\", "b", "c\\\\", "d"), SelectorUtils.parseSelectors("a\\.b.c\\\\.d"));
    }

    @Test
    public void testURLDecodingWhenParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of("csvReplaceColumnLabels: ID=", "csvReplaceColumnLabels:@=#", "csv"),
            SelectorUtils
                .parseSelectors(".csvReplaceColumnLabels:%20ID%3D.csvReplaceColumnLabels:%40%3D%23.csv"));
    }

    @Test
    public void testDepthParsing()
        throws Exception
    {
        Assertions.assertEquals(0, SelectorUtils.parseDepth(".0.json").getAsInt());
        Assertions.assertEquals(1, SelectorUtils.parseDepth(".1.json").getAsInt());
        Assertions.assertEquals(42, SelectorUtils.parseDepth(".42.json").getAsInt());
    }

    @Test
    public void testInfinityDepthParsing()
        throws Exception
    {
        Assertions.assertEquals(SelectorUtils.UNLIMITED_DEPTH, SelectorUtils.parseDepth(".infinity.json").getAsInt());
        Assertions.assertEquals(SelectorUtils.UNLIMITED_DEPTH, SelectorUtils.parseDepth(".Infinity.json").getAsInt());
        // A negative number also means unlimited depth
        Assertions.assertEquals(SelectorUtils.UNLIMITED_DEPTH, SelectorUtils.parseDepth(".-1.json").getAsInt());
    }

    @Test
    public void testMissingDepthParsing()
        throws Exception
    {
        Assertions.assertTrue(SelectorUtils.parseDepth(null).isEmpty());
        Assertions.assertTrue(SelectorUtils.parseDepth("").isEmpty());
        Assertions.assertTrue(SelectorUtils.parseDepth(".deep.json").isEmpty());
        // Only whole numbers are depths
        Assertions.assertTrue(SelectorUtils.parseDepth(".1b.2,5.v3.json").isEmpty());
        // Numbers too large to be a real depth are ignored, instead of overflowing
        Assertions.assertTrue(SelectorUtils.parseDepth(".12345678901234567890.json").isEmpty());
    }

    @Test
    public void testLastDepthWinsWhenParsing()
        throws Exception
    {
        Assertions.assertEquals(2, SelectorUtils.parseDepth(".3.deep.2.1b.json").getAsInt());
        Assertions.assertEquals(2, SelectorUtils.parseDepth(".infinity.2.json").getAsInt());
        Assertions.assertEquals(SelectorUtils.UNLIMITED_DEPTH, SelectorUtils.parseDepth(".2.infinity.json").getAsInt());
    }

    @Test
    public void testSimpleOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(Pair.of("a", "1"), Pair.of("b", "2")),
            SelectorUtils.parseOptions("dataFilter:", ".data.dataFilter:a=1.dataOption:c=3.dataFilter:b=2"));
    }

    @Test
    public void testEmptyStringOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseOptions("a", ""));
    }

    @Test
    public void testNullStringOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseOptions("a", null));
    }

    @Test
    public void testNullPrefixOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseOptions(null, ":b=c"));
    }

    @Test
    public void testEmptyPrefixOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(), SelectorUtils.parseOptions("", ":b=c"));
    }

    @Test
    public void testMissinColonOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(Pair.of("a", "1"), Pair.of("b", "2")),
            SelectorUtils.parseOptions("dataFilter", ".data.dataFilter:a=1.dataOption:c=3.dataFilter:b=2"));
    }

    @Test
    public void testOptionParsingUsesFirstEquals()
        throws Exception
    {
        Assertions.assertEquals(List.of(Pair.of("a", "1=1"), Pair.of("b", "2=2")),
            SelectorUtils.parseOptions("dataFilter", ".data.dataFilter:a=1=1.dataFilter:b=2=2"));
    }

    @Test
    public void testValuelessOptionParsing()
        throws Exception
    {
        Assertions.assertEquals(List.of(Pair.of("a", ""), Pair.of("b", "")),
            SelectorUtils.parseOptions("dataFilter", ".data.dataFilter:a.dataFilter:b"));
    }

    @Test
    public void testRealOptionParsing()
        throws Exception
    {
        final String pathInfo = ".data.deep.dataFilter:status=SUBMITTED.json";
        final List<String> dotted = List.of("dataOption:formSelectors=deep.-identify.simple",
            "dataFilter:createdAfter=2025-01-01T00:00:00.000-05:00");
        Assertions.assertEquals(
            List.of("data",
                "deep",
                "dataFilter:status=SUBMITTED",
                "json",
                "dataOption:formSelectors=deep.-identify.simple",
                "dataFilter:createdAfter=2025-01-01T00:00:00.000-05:00"),
            SelectorUtils.withSelectors(dotted, () -> SelectorUtils.parseSelectors(pathInfo)));
        Assertions.assertEquals(List.of(Pair.of("formSelectors", "deep.-identify.simple")),
            SelectorUtils.withSelectors(dotted, () -> SelectorUtils.parseOptions("dataOption:", pathInfo)));
        Assertions.assertEquals(
            List.of(
                Pair.of("status", "SUBMITTED"),
                Pair.of("createdAfter", "2025-01-01T00:00:00.000-05:00")),
            SelectorUtils.withSelectors(dotted, () -> SelectorUtils.parseOptions("dataFilter:", pathInfo)));
    }

    @Test
    public void testURLDecodingWhenParsingOptions()
        throws Exception
    {
        Assertions.assertEquals(List.of(Pair.of(" ID", ""), Pair.of("@", "#")),
            SelectorUtils
                .parseOptions("csvReplaceColumnLabels",
                    ".csvReplaceColumnLabels:%20ID%3D.csvReplaceColumnLabels:%40%3D%23.csv"));
    }

    @Test
    public void testParseToMap()
    {
        Assertions.assertEquals(Map.of("formSelectors", "deep.-identify.simple", "descendantData", "2"),
            SelectorUtils.withSelectors(List.of("dataOption:formSelectors=deep.-identify.simple"),
                () -> SelectorUtils.parseOptionsToMap("dataOption:",
                    ".data"
                        + ".dataOption:descendantData=true"
                        + ".dataOption:descendantData=5"
                        + ".dataOption:descendantData=2")));
    }

    @Test
    public void testRequestSelectorsAreIncludedAndComeLast()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("deep", "-labels"));

            Assertions.assertEquals(List.of("data", "csv", "deep", "-labels"),
                SelectorUtils.parseSelectors(".data.csv"));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testRequestSelectorsApplyWithNoPathInfoAtAll()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("deep"));

            Assertions.assertEquals(List.of("deep"), SelectorUtils.parseSelectors(""));
            Assertions.assertEquals(List.of("deep"), SelectorUtils.parseSelectors(null));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testBlankRequestSelectorsAreIgnored()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("", "  ", "deep"));

            Assertions.assertEquals(List.of("deep"), SelectorUtils.parseSelectors(null));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testNoRequestSelectorsChangesNothing()
    {
        SelectorUtils.setRequestSelectors(null);
        try {
            Assertions.assertEquals(List.of("data", "csv"), SelectorUtils.parseSelectors(".data.csv"));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
        // And with nothing ever recorded on this thread
        Assertions.assertEquals(List.of("data", "csv"), SelectorUtils.parseSelectors(".data.csv"));
    }

    @Test
    public void testARequestSelectorNeedsNoEscaping()
    {
        // Nothing splits a request selector at its dots
        try {
            SelectorUtils.setRequestSelectors(List.of("dataOption:formSelectors=-dereference.simple.deep"));

            Assertions.assertEquals(Map.of("formSelectors", "-dereference.simple.deep"),
                SelectorUtils.parseOptionsToMap("dataOption:", ".data.csv"));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testARequestOptionAppliesWithNoOptionsInThePath()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("dataFilter:status=SUBMITTED"));

            Assertions.assertEquals(List.of(Pair.of("status", "SUBMITTED")),
                SelectorUtils.parseOptions("dataFilter:", ".json"));
            // Even when there is no path info to parse at all
            Assertions.assertEquals(List.of(Pair.of("status", "SUBMITTED")),
                SelectorUtils.parseOptions("dataFilter:", null));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testARequestDepthOverridesThePathDepth()
    {
        // Request selectors come last, and the last depth wins, so an explicit parameter takes precedence
        try {
            SelectorUtils.setRequestSelectors(List.of("2"));

            Assertions.assertEquals(2, SelectorUtils.parseDepth(".5.json").getAsInt());
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testAnEmptyOptionPrefixIsStillRefused()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("dataFilter:status=SUBMITTED"));

            Assertions.assertEquals(List.of(), SelectorUtils.parseOptions("", ".json"));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testAnOptionValueKeepsItsBackslashes()
    {
        Assertions.assertEquals(List.of(Pair.of("path", "C:\\x\\\\y=z")),
            SelectorUtils.withSelectors(List.of("dataFilter:path=C:\\x\\\\y=z"),
                () -> SelectorUtils.parseOptions("dataFilter:", null)));
    }

    @Test
    public void testExplicitSelectorsReplaceTheRequestOnesUntilTheCodeReturns()
    {
        try {
            SelectorUtils.setRequestSelectors(List.of("deep"));

            Assertions.assertEquals(List.of("json", "2"),
                SelectorUtils.withSelectors(List.of("2"), () -> SelectorUtils.parseSelectors(".json")));
            Assertions.assertEquals(List.of("json", "deep"), SelectorUtils.parseSelectors(".json"));
        } finally {
            SelectorUtils.clearRequestSelectors();
        }
    }

    @Test
    public void testExplicitSelectorsLeaveNothingBehind()
    {
        Assertions.assertEquals(List.of("deep"),
            SelectorUtils.withSelectors(List.of("deep"), () -> SelectorUtils.parseSelectors(null)));
        Assertions.assertEquals(List.of(), SelectorUtils.parseSelectors(null));
    }

    @Test
    public void testExplicitSelectorsAreClearedWhenTheCodeThrows()
    {
        final IllegalStateException thrown = new IllegalStateException();
        Assertions.assertSame(thrown, Assertions.assertThrows(IllegalStateException.class,
            () -> SelectorUtils.withSelectors(List.of("deep"), () -> {
                throw thrown;
            })));
        Assertions.assertEquals(List.of(), SelectorUtils.parseSelectors(null));
    }
}
