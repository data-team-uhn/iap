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
package io.uhndata.iap.search.api;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SearchUtils}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SearchUtilsTest
{
    private static final String PATH = "/Submissions/s1";

    private static final JsonObject RESULT = Json.createObjectBuilder().add("@name", "s1").build();

    @Test
    public void likePatternsEscapeTheirWildcards()
    {
        Assertions.assertEquals("100\\% of a\\_b", SearchUtils.escapeLikeText("100% of a_b"));
        // A backslash is the escape character, so it has to be escaped itself
        Assertions.assertEquals("a\\\\b", SearchUtils.escapeLikeText("a\\b"));
        // A quote is not a pattern character; quoting is escapeQueryArgument's job
        Assertions.assertEquals("it's", SearchUtils.escapeLikeText("it's"));
    }

    @Test
    public void queryArgumentsDoubleTheirQuotes()
    {
        Assertions.assertEquals("it''s", SearchUtils.escapeQueryArgument("it's"));
        Assertions.assertEquals("plain", SearchUtils.escapeQueryArgument("plain"));
    }

    @Test
    public void singleValuesAreMatchedCaseInsensitively()
    {
        Assertions.assertEquals("Hello world", SearchUtils.getMatch("Hello world", "LO WO"));
        Assertions.assertNull(SearchUtils.getMatch("Hello world", "bye"));
        Assertions.assertNull(SearchUtils.getMatch(null, "anything"));
    }

    @Test
    public void multiValuedPropertiesReturnTheMatchingValue()
    {
        Assertions.assertEquals("second", SearchUtils.getMatch(new String[] { "first", "second" }, "eco"));
        // Whatever the property's type, the values are compared as text
        Assertions.assertEquals("42", SearchUtils.getMatch(new Long[] { 7L, 42L }, "4"));
        Assertions.assertNull(SearchUtils.getMatch(new String[] { "first", "second" }, "third"));
    }

    @Test
    public void arrayMatchingHandlesNoValues()
    {
        Assertions.assertNull(SearchUtils.getMatchFromArray(null, "anything"));
        Assertions.assertNull(SearchUtils.getMatchFromArray(new String[0], "anything"));
    }

    @Test
    public void matchMetadataDescribesTheMatchInContext()
    {
        final JsonObject described = SearchUtils.addMatchMetadata(RESULT, "The quick brown fox", "quick",
            "Title", PATH);
        final JsonObject match = described.getJsonObject(SearchUtils.MATCH_KEY);
        Assertions.assertEquals("s1", described.getString("@name"));
        Assertions.assertEquals("Title", match.getString("label"));
        Assertions.assertEquals(PATH, match.getString("@path"));
        Assertions.assertEquals("The ", match.getString("before"));
        Assertions.assertEquals("quick", match.getString("text"));
        Assertions.assertEquals(" brown f...", match.getString("after"));
    }

    @Test
    public void longContextIsTrimmedOnBothSides()
    {
        final JsonObject match = SearchUtils
            .addMatchMetadata(RESULT, "0123456789ABCDEFneedle0123456789ABCDEF", "needle", null, null)
            .getJsonObject(SearchUtils.MATCH_KEY);
        Assertions.assertEquals("...89ABCDEF", match.getString("before"));
        Assertions.assertEquals("01234567...", match.getString("after"));
        Assertions.assertEquals("", match.getString("label"));
        Assertions.assertEquals("", match.getString("@path"));
    }

    @Test
    public void theMatchedTextIsQuotedAsStored()
    {
        final JsonObject match = SearchUtils.addMatchMetadata(RESULT, "A Needle here", "needle", null, null)
            .getJsonObject(SearchUtils.MATCH_KEY);
        Assertions.assertEquals("Needle", match.getString("text"));
    }

    @Test
    public void aValueThatDoesNotMatchIsNotDescribed()
    {
        // The caller is expected to pass a value that matched, but a description of a match that isn't there would
        // be nonsense, so the result is returned as it is rather than made up
        Assertions.assertEquals(RESULT, SearchUtils.addMatchMetadata(RESULT, "nothing here", "needle", null, null));
    }

    @Test
    public void nodeNamesAreValidated()
    {
        Assertions.assertTrue(SearchUtils.isValidNodeName("question"));
        Assertions.assertTrue(SearchUtils.isValidNodeName("sub:Submission"));
        Assertions.assertFalse(SearchUtils.isValidNodeName("a/b"));
        Assertions.assertFalse(SearchUtils.isValidNodeName(""));
        Assertions.assertFalse(SearchUtils.isValidNodeName(null));
    }
}
