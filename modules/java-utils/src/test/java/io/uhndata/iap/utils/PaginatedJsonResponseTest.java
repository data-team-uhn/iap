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

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PaginatedJsonResponse}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class PaginatedJsonResponseTest
{
    private static final String ROWS = "rows";

    private static final String TOTAL = "totalrows";

    private static final String RETURNED = "returnedrows";

    private static final String APPROXIMATE = "totalIsApproximate";

    private static final String A = "/a";

    private StringWriter output;

    private JsonGenerator json;

    private SlingJakartaHttpServletRequest request;

    /** The keys the serializer was actually invoked for, to check that skipped results are never serialized. */
    private List<String> serialized;

    @BeforeEach
    public void setup()
    {
        this.output = new StringWriter();
        this.json = Json.createGenerator(this.output);
        this.request = Mockito.mock(SlingJakartaHttpServletRequest.class);
        this.serialized = new ArrayList<>();
    }

    @Test
    public void requestWithoutParametersUsesDefaults()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forRequest(this.json, this.request));
        offerAll(page, 3);
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(0, result.getInt("offset"));
        Assertions.assertEquals(PaginatedJsonResponse.DEFAULT_LIMIT, result.getInt("limit"));
        Assertions.assertEquals(3, result.getJsonArray(ROWS).size());
        Assertions.assertFalse(result.containsKey("req"));
    }

    @Test
    public void requestParametersAreHonoured()
    {
        Mockito.when(this.request.getParameter("offset")).thenReturn("2");
        Mockito.when(this.request.getParameter("limit")).thenReturn("3");
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forRequest(this.json, this.request));
        offerAll(page, 10);
        final JsonObject result = finish(page, "17");
        Assertions.assertEquals("17", result.getString("req"));
        Assertions.assertEquals(2, result.getInt("offset"));
        Assertions.assertEquals(3, result.getInt("limit"));
        Assertions.assertEquals(3, result.getInt(RETURNED));
        Assertions.assertEquals(10, result.getInt(TOTAL));
        Assertions.assertEquals(List.of("/r2", "/r3", "/r4"), pathsOf(result));
        // The results outside the page are counted, but never serialized
        Assertions.assertEquals(List.of("/r2", "/r3", "/r4"), this.serialized);
    }

    @Test
    public void invalidNumbersFallBackToDefaults()
    {
        Mockito.when(this.request.getParameter("offset")).thenReturn("-5");
        Mockito.when(this.request.getParameter("limit")).thenReturn("not a number");
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forRequest(this.json, this.request));
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(0, result.getInt("offset"));
        Assertions.assertEquals(PaginatedJsonResponse.DEFAULT_LIMIT, result.getInt("limit"));
    }

    @Test
    public void oversizedLimitIsCapped()
    {
        Mockito.when(this.request.getParameter("limit")).thenReturn("100000");
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forRequest(this.json, this.request));
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(PaginatedJsonResponse.MAX_LIMIT, result.getInt("limit"));
    }

    @Test
    public void zeroLimitOnlyCounts()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 0));
        offerAll(page, 5);
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(0, result.getJsonArray(ROWS).size());
        Assertions.assertEquals(0, result.getInt(RETURNED));
        Assertions.assertEquals(5, result.getInt(TOTAL));
        Assertions.assertEquals(List.of(), this.serialized);
    }

    @Test
    public void duplicatesAreCountedOnce()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 10));
        page.offer(A, () -> row(A));
        page.offer("/b", () -> row("/b"));
        page.offer(A, () -> row(A));
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(List.of(A, "/b"), pathsOf(result));
        Assertions.assertEquals(2, result.getInt(TOTAL));
    }

    @Test
    public void nullKeySkipsDeduplication()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 10));
        page.offer(null, () -> row(A));
        page.offer(null, () -> row(A));
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(List.of(A, A), pathsOf(result));
        Assertions.assertEquals(2, result.getInt(TOTAL));
    }

    @Test
    public void unserializableResultsAreCountedButLeftOut()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 10));
        page.offer(A, () -> row(A));
        page.offer("/b", () -> null);
        page.offer("/c", () -> row("/c"));
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(List.of(A, "/c"), pathsOf(result));
        Assertions.assertEquals(2, result.getInt(RETURNED));
        Assertions.assertEquals(3, result.getInt(TOTAL));
    }

    @Test
    public void countingStopsPastTheLookahead()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 1));
        // With a limit of 1, counting covers the first LOOKAHEAD_PAGES pages, plus one result proving there are more
        final long lookahead = PaginatedJsonResponse.LOOKAHEAD_PAGES;
        for (int i = 0; i < lookahead; ++i) {
            Assertions.assertTrue(page.offer("/r" + i, () -> row("/x")), "The page filled up too early");
            Assertions.assertFalse(page.isFull());
        }
        Assertions.assertFalse(page.offer("/last", () -> row("/x")), "The page should have filled up");
        Assertions.assertTrue(page.isFull());
        final JsonObject result = finish(page, null);
        // The extra result is not part of the reported total, it only proves the total is approximate
        Assertions.assertEquals(lookahead, result.getInt(TOTAL));
        Assertions.assertTrue(result.getBoolean(APPROXIMATE));
    }

    @Test
    public void anExactTotalIsNotApproximate()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 1));
        offerAll(page, 2);
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(2, result.getInt(TOTAL));
        Assertions.assertFalse(result.getBoolean(APPROXIMATE));
    }

    @Test
    public void aFullPageRejectsFurtherResults()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 0));
        // A limit of 0 still counts a whole batch of pages before giving up
        while (!page.isFull()) {
            page.offer(null, () -> row("/x"));
        }
        Assertions.assertFalse(page.offer(A, () -> row(A)));
        Assertions.assertFalse(page.offer(A, () -> row(A)), "A duplicate must not revive a full page");
    }

    @Test
    public void aSourceThatMustBeToldToAdvanceIsSkippedInsteadOfRead()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 1, 1));
        final List<String> skipped = new ArrayList<>();
        for (int i = 0; i < 3; ++i) {
            final String path = "/r" + i;
            page.offer(null, () -> row(path), () -> skipped.add(path));
        }
        final JsonObject result = finish(page, null);
        Assertions.assertEquals(List.of("/r1"), pathsOf(result));
        // Exactly one of the two callbacks runs for every result
        Assertions.assertEquals(List.of("/r1"), this.serialized);
        Assertions.assertEquals(List.of("/r0", "/r2"), skipped);
        Assertions.assertEquals(3, result.getInt(TOTAL));
    }

    @Test
    public void aDuplicateIsNeitherReadNorSkipped()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 10));
        final List<String> skipped = new ArrayList<>();
        page.offer(A, () -> row(A), () -> skipped.add(A));
        page.offer(A, () -> row(A), () -> skipped.add(A));
        finish(page, null);
        // The caller had to read the result to have its key, so there is nothing left to skip
        Assertions.assertEquals(List.of(), skipped);
        Assertions.assertEquals(List.of(A), this.serialized);
    }

    @Test
    public void remainingCapacityShrinksToZero()
    {
        final PaginatedJsonResponse page = startPage(PaginatedJsonResponse.forPage(this.json, 0, 1));
        final long initial = page.getRemainingCapacity();
        Assertions.assertEquals(PaginatedJsonResponse.LOOKAHEAD_PAGES + 1, initial);
        offerAll(page, 1);
        Assertions.assertEquals(initial - 1, page.getRemainingCapacity());
        while (!page.isFull()) {
            page.offer(null, () -> row("/x"));
        }
        Assertions.assertEquals(0, page.getRemainingCapacity());
    }

    @Test
    public void missingSelectorsAreEmpty()
    {
        Assertions.assertEquals("", PaginatedJsonResponse.getResourceSelectors(this.request));
        Mockito.when(this.request.getParameter("resourceSelectors")).thenReturn("  ");
        Assertions.assertEquals("", PaginatedJsonResponse.getResourceSelectors(this.request));
    }

    @Test
    public void selectorsAreCleanedUp()
    {
        Mockito.when(this.request.getParameter("resourceSelectors")).thenReturn("deep");
        Assertions.assertEquals(".deep", PaginatedJsonResponse.getResourceSelectors(this.request));
        // Path separators and whitespace would let a caller reach a different resource altogether
        Mockito.when(this.request.getParameter("resourceSelectors")).thenReturn("../..\t/other..deep");
        Assertions.assertEquals(".other.deep", PaginatedJsonResponse.getResourceSelectors(this.request));
    }

    @Test
    public void errorsAreWrittenAsJson() throws Exception
    {
        final SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(this.output));
        PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST, "Bad query");
        Mockito.verify(response).setStatus(SlingJakartaHttpServletResponse.SC_BAD_REQUEST);
        Assertions.assertEquals("Bad query", parse().getString("error"));
    }

    @Test
    public void errorsWithoutAMessageGetADefaultOne() throws Exception
    {
        final SlingJakartaHttpServletResponse response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(this.output));
        PaginatedJsonResponse.writeError(response, SlingJakartaHttpServletResponse.SC_BAD_REQUEST, null);
        Assertions.assertEquals("Invalid request", parse().getString("error"));
    }

    @Test
    public void numbersFallBackToTheDefault()
    {
        Assertions.assertEquals(7, PaginatedJsonResponse.parseLong("7", 3));
        Assertions.assertEquals(3, PaginatedJsonResponse.parseLong("seven", 3));
        Assertions.assertEquals(3, PaginatedJsonResponse.parseLong(null, 3));
    }

    private PaginatedJsonResponse startPage(final PaginatedJsonResponse page)
    {
        this.json.writeStartObject();
        this.json.writeStartArray(ROWS);
        return page;
    }

    private JsonObject finish(final PaginatedJsonResponse page, final String requestId)
    {
        this.json.writeEnd();
        page.writeSummary(requestId);
        this.json.writeEnd().flush();
        this.json.close();
        return parse();
    }

    private JsonObject parse()
    {
        return Json.createReader(new StringReader(this.output.toString())).readObject();
    }

    private void offerAll(final PaginatedJsonResponse page, final int count)
    {
        for (int i = 0; i < count; ++i) {
            final String path = "/r" + i;
            page.offer(path, () -> row(path));
        }
    }

    private JsonObject row(final String path)
    {
        this.serialized.add(path);
        return Json.createObjectBuilder().add("path", path).build();
    }

    private List<String> pathsOf(final JsonObject result)
    {
        return result.getJsonArray(ROWS).getValuesAs(JsonObject.class).stream()
            .map(row -> row.getString("path"))
            .toList();
    }
}
