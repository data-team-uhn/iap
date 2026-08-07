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

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The response conventions shared by the endpoints that return a page of results as JSON: how {@code offset} and
 * {@code limit} are read from the request, how many results are counted before the total is called approximate, and
 * what the response looks like.
 *
 * <p>
 * A successful response is a JSON object holding a {@code rows} array followed by a summary:
 * </p>
 *
 * <pre>
 * {
 *   "rows": [ … ],
 *   "req": "17",           // only when the request sent one
 *   "offset": 0,
 *   "limit": 10,
 *   "returnedrows": 10,
 *   "totalrows": 42,
 *   "totalIsApproximate": false
 * }
 * </pre>
 *
 * <p>
 * The caller writes the {@code rows} array itself and {@link #offer offers} each candidate result to this class, which
 * decides whether it belongs on the requested page, serializes it if so, and keeps the counts. Counting stops
 * {@value #LOOKAHEAD_PAGES} pages past the requested one: a repository can hold far more matches than anyone will page
 * through, so past that point the exact total is not worth the reads, and {@code totalIsApproximate} says so.
 * </p>
 *
 * <p>
 * A typical caller looks like:
 * </p>
 *
 * <pre>
 * try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
 *     json.writeStartObject();
 *     json.writeStartArray("rows");
 *     final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
 *     while (results.hasNext() &amp;&amp; page.offer(key, () -&gt; serialize(results.next()))) {
 *         // Everything happens in offer()
 *     }
 *     json.writeEnd();
 *     page.writeSummary(request.getParameter("req"));
 *     json.writeEnd().flush();
 * }
 * </pre>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PaginatedJsonResponse
{
    /** The number of results returned when the request doesn't ask for a specific {@code limit}: {@value}. */
    public static final long DEFAULT_LIMIT = 10;

    /** The number of results returned at most, no matter how large a {@code limit} the request asks for: {@value}. */
    public static final long MAX_LIMIT = 1000;

    /**
     * How far past the requested page, in pages, to keep counting matches before declaring the total approximate:
     * {@value}. Counting means iterating the query results, which is cheap but not free; a deep horizon keeps the
     * reported total exact for all but the largest collections, and a good estimate beyond that.
     */
    public static final long LOOKAHEAD_PAGES = 100;

    /**
     * The most results one counting batch may span, whatever the requested page size: {@value}. Without this bound, a
     * maximum-limit request could demand counting a hundred thousand results in one go.
     */
    public static final long MAX_LOOKAHEAD_ROWS = 10_000;

    private final JsonGenerator json;

    private final long offset;

    private final long limit;

    /** The number of results to count before giving up on an exact total. */
    private final long lookahead;

    /** The keys of the results seen so far, for dropping duplicates. */
    private final Set<String> seen = new HashSet<>();

    /** The number of results written into the {@code rows} array. */
    private long returned;

    /** The number of distinct results seen, whether they were written or not. */
    private long counted;

    /** Whether there were still results left when counting stopped. */
    private boolean more;

    private PaginatedJsonResponse(final JsonGenerator json, final long offset, final long limit)
    {
        this.json = json;
        this.offset = offset;
        this.limit = limit;
        // Count until the end of the batch of pages containing the requested page, plus one more result to know
        // whether the reported total is exact. A limit of 0 asks for a count only, but still needs a page size.
        final long batchSize = Math.min(LOOKAHEAD_PAGES * Math.max(limit, 1), MAX_LOOKAHEAD_ROWS);
        this.lookahead = ((offset + Math.max(limit, 1) + batchSize - 1) / batchSize) * batchSize + 1;
    }

    /**
     * Starts a page as requested by the client: the {@code offset} and {@code limit} request parameters, corrected to
     * sane values.
     *
     * @param json the generator to write the results into, positioned inside the {@code rows} array
     * @param request the current request
     * @return a paginator for the requested page
     */
    @NotNull
    public static PaginatedJsonResponse forRequest(@NotNull final JsonGenerator json,
        @NotNull final SlingJakartaHttpServletRequest request)
    {
        final long offset = Math.max(0, parseLong(request.getParameter("offset"), 0));
        final long limit = Math.min(Math.max(0, parseLong(request.getParameter("limit"), DEFAULT_LIMIT)), MAX_LIMIT);
        return new PaginatedJsonResponse(json, offset, limit);
    }

    /**
     * Starts an explicitly sized page. The caller is responsible for the values being sane; prefer
     * {@link #forRequest} when they come from a request.
     *
     * @param json the generator to write the results into, positioned inside the {@code rows} array
     * @param offset how many results to skip, {@code 0} or more
     * @param limit how many results to write at most, {@code 0} or more
     * @return a paginator for the requested page
     */
    @NotNull
    public static PaginatedJsonResponse forPage(@NotNull final JsonGenerator json, final long offset, final long limit)
    {
        return new PaginatedJsonResponse(json, offset, limit);
    }

    /**
     * The extra serialization selectors requested by the client, cleaned up for appending to a resource path.
     *
     * @param request the current request
     * @return a string safe to append to a repository path, either empty or in the form {@code .sel1.sel2}
     */
    @NotNull
    public static String getResourceSelectors(@NotNull final SlingJakartaHttpServletRequest request)
    {
        final String selectors = request.getParameter("resourceSelectors");
        if (selectors == null || selectors.isBlank()) {
            return "";
        }
        return ("." + selectors).replaceAll("[/\\s]", "").replaceAll("\\.+", ".");
    }

    /**
     * Writes an error response as a small JSON object. Anything already written to the response is left as it is, so
     * this must be called before the results start being written.
     *
     * @param response the HTTP response
     * @param status the HTTP status code to send
     * @param message the error message to include in the response
     * @throws IOException if writing the response fails
     */
    public static void writeError(@NotNull final SlingJakartaHttpServletResponse response, final int status,
        @Nullable final String message) throws IOException
    {
        response.setStatus(status);
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.write("error", Objects.requireNonNullElse(message, "Invalid request"));
            json.writeEnd().flush();
        }
    }

    /**
     * Converts a request parameter, which may be missing or invalid, into a proper long, with fallback to a default
     * value.
     *
     * @param value the string to convert, may be {@code null} or not a number
     * @param defaultValue the value to use if the input cannot be converted to a number
     * @return the parsed input, if valid, or the default value
     */
    public static long parseLong(@Nullable final String value, final long defaultValue)
    {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Offers one result to the page. Results before the requested offset and after the requested limit are only
     * counted, so the serializer is only invoked for the results that actually end up in the response.
     *
     * @param key a value uniquely identifying the result, so that a result reached twice — which a query with a join
     *            does routinely — is only counted and returned once; {@code null} skips the duplicate check, for
     *            sources that cannot produce the same result twice
     * @param serializer computes the JSON for the result; returning {@code null} leaves the result out of the
     *            response, although it still counts towards the total
     * @return {@code true} if more results are wanted, {@code false} once enough have been seen and the caller should
     *         stop; the same answer as a subsequent {@link #isFull()}
     */
    public boolean offer(@Nullable final String key, @NotNull final Supplier<JsonObject> serializer)
    {
        return offer(key, serializer, () -> {
            // The caller has already moved past this result on its own
        });
    }

    /**
     * Offers one result to the page, for a source that only moves forward when it is told to. Exactly one of the two
     * callbacks is invoked for every result that isn't a duplicate: the serializer for the results that go into the
     * response, the skipper for the ones that are only counted.
     *
     * @param key a value uniquely identifying the result, or {@code null} to skip the duplicate check; a caller that
     *            has a key has necessarily already read the result, so the skipper is not called for a duplicate
     * @param serializer reads the result and computes its JSON; returning {@code null} leaves the result out of the
     *            response, although it still counts towards the total
     * @param skipper moves past the result without reading it
     * @return {@code true} if more results are wanted, {@code false} once enough have been seen
     */
    public boolean offer(@Nullable final String key, @NotNull final Supplier<JsonObject> serializer,
        @NotNull final Runnable skipper)
    {
        if (this.more) {
            return false;
        }
        if (key != null && !this.seen.add(key)) {
            return true;
        }
        ++this.counted;
        if (this.counted > this.offset && this.returned < this.limit) {
            final JsonObject row = serializer.get();
            if (row != null) {
                this.json.write(row);
                ++this.returned;
            }
        } else {
            skipper.run();
        }
        this.more = this.counted >= this.lookahead;
        return !this.more;
    }

    /**
     * Whether enough results have been seen, so that offering more cannot change the response. A caller pulling from
     * several sources should check this before starting on the next one.
     *
     * @return {@code true} if no further results are wanted
     */
    public boolean isFull()
    {
        return this.more;
    }

    /**
     * How many more results can still change the response. A caller asking a source for results should not ask for
     * more than this many, since anything past them is discarded.
     *
     * @return the number of results still wanted, {@code 0} once the page {@link #isFull() is full}
     */
    public long getRemainingCapacity()
    {
        return Math.max(0, this.lookahead - this.counted);
    }

    /**
     * Writes the summary of the page: the effective offset and limit, the number of returned results, and the
     * (possibly approximate) total number of matches. Must be called after the {@code rows} array has been closed.
     *
     * @param requestId the opaque {@code req} request parameter, echoed back so that the client can match the
     *            response to its request, or discard an out-of-order one; not written when {@code null}
     */
    public void writeSummary(@Nullable final String requestId)
    {
        if (requestId != null) {
            this.json.write("req", requestId);
        }
        this.json.write("offset", this.offset);
        this.json.write("limit", this.limit);
        this.json.write("returnedrows", this.returned);
        // The one result read past the lookahead proves there are more, but isn't itself part of the total
        this.json.write("totalrows", this.more ? this.counted - 1 : this.counted);
        this.json.write("totalIsApproximate", this.more);
    }
}
