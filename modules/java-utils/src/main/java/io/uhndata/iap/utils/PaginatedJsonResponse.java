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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes one page of results as JSON, with a summary that counts every match. It reads {@code offset} and
 * {@code limit} from the request, decides which results go on the page, and writes the summary after them.
 *
 * <p>
 * A successful response is a JSON object holding a {@code rows} array followed by a summary:
 * </p>
 *
 * {@snippet lang=json :
 * {
 *   "rows": [ … ],
 *   "req": "17",           // only when the request sent one
 *   "offset": 0,
 *   "limit": 10,
 *   "returnedrows": 10,
 *   "totalrows": 42,
 *   "totalIsApproximate": false
 * }
 * }
 *
 * <p>
 * The caller opens the {@code rows} array and {@link #offer offers} each result in turn. This class serializes the
 * results that fall on the requested page, and counts the rest. Counting stops at the end of the batch of
 * {@value #LOOKAHEAD_PAGES} pages that holds the requested page. A total cut short there has
 * {@code totalIsApproximate} set.
 * </p>
 *
 * <p>
 * No request counts past {@value #MAX_COUNT} results, however large a page it asks for or however far into the
 * results it starts. A page starting near that ceiling comes back short, and one starting past it comes back empty.
 * </p>
 *
 * <p>
 * A typical caller looks like:
 * </p>
 *
 * {@snippet lang=java :
 * try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
 *     json.writeStartObject();
 *     json.writeStartArray("rows");
 *     final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
 *     boolean more = true;
 *     while (results.hasNext() && more) {
 *         // Advance the iterator here: the serializer runs only for the results on the page
 *         final Result result = results.next();
 *         more = page.offer(result.getKey(), () -> serialize(result));
 *     }
 *     json.writeEnd();
 *     page.writeSummary(request.getParameter("req"));
 *     json.writeEnd().flush();
 * }
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PaginatedJsonResponse
{
    /** The number of results on a page when the request gives no {@code limit}: {@value}. */
    public static final long DEFAULT_LIMIT = 10;

    /** The most results a page holds, whatever {@code limit} the request gives: {@value}. */
    public static final long MAX_LIMIT = 1000;

    /** The size, in pages, of the batch counted before the total is called approximate: {@value}. */
    public static final long LOOKAHEAD_PAGES = 100;

    /**
     * The most results a single request counts, whatever it asks for: {@value}. {@link #MAX_LIMIT} caps the
     * {@code limit}, but the client also chooses the {@code offset}. Without this ceiling, one request could walk the
     * whole repository and hold a key for each match. It also caps the counting batch, which can otherwise reach
     * {@value #LOOKAHEAD_PAGES} pages of {@value #MAX_LIMIT} results.
     */
    public static final long MAX_COUNT = 10_000;

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
        // Count to the end of the batch holding the requested page, plus one result to tell whether the total is
        // exact. A limit of 0 asks for a count only, and counts as far as any request may.
        final long batch = limit > 0 ? Math.min(limit, MAX_LIMIT) : MAX_LIMIT;
        final long pageSize = LOOKAHEAD_PAGES * batch;
        // The summary reports the offset as given, so it is clamped only here. forPage passes any long through, and
        // clamping both terms keeps the lookahead from wrapping negative.
        final long wanted = Math.max(0, Math.min(offset, MAX_COUNT)) + batch;
        this.lookahead = Math.min(MAX_COUNT, ((wanted + pageSize - 1) / pageSize) * pageSize) + 1;
    }

    /**
     * Starts the page the client asked for, from the {@code offset} and {@code limit} request parameters. A missing
     * or invalid value takes its default, a negative one counts as {@code 0}, and the limit is capped at
     * {@value #MAX_LIMIT}.
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
     * Starts a page of the given size. The values are used as given, so use {@link #forRequest} for values from a
     * request.
     *
     * @param json the generator to write the results into, positioned inside the {@code rows} array
     * @param offset how many results to skip, {@code 0} or more
     * @param limit how many results to write at most, {@code 0} or more; {@code 0} only counts them
     * @return a paginator for the requested page
     */
    @NotNull
    public static PaginatedJsonResponse forPage(@NotNull final JsonGenerator json, final long offset, final long limit)
    {
        return new PaginatedJsonResponse(json, offset, limit);
    }

    /**
     * The serialization selectors the client gives in the {@code resourceSelectors} parameter, to append to the path
     * of each result. Slashes and whitespace are removed, and runs of dots become one.
     *
     * @param request the current request
     * @return the selectors after a dot, such as {@code .deep.simple}; empty if the parameter is missing or blank
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
     * Reads a request parameter as a {@code long}.
     *
     * @param value the parameter's value; {@code null} if the request does not give it
     * @param defaultValue the value to use if the parameter is missing or not a whole number
     * @return the number the parameter holds, or the default
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
     * Offers one result to the page. A result before the requested offset or past the limit is only counted. The
     * serializer runs only for the results on the page.
     *
     * @param key a value unique to the result, such as its path. A result offered twice with the same key, as a query
     *            with a join can return it, is counted and returned once. {@code null} skips the duplicate check, for
     *            a source that never returns the same result twice
     * @param serializer computes the JSON for the result; returning {@code null} leaves the result out of the
     *            response, although it still counts towards the total
     * @return {@code true} if more results are wanted; {@code false} once enough have been seen, and then
     *         {@link #isFull()} is {@code true}
     */
    public boolean offer(@Nullable final String key, @NotNull final Supplier<JsonObject> serializer)
    {
        return offer(key, serializer, () -> {
            // A caller that has no skipper has already moved past the result
        });
    }

    /**
     * Offers one result to the page, for a source that moves forward only when it is told to. For each result that is
     * not a duplicate, exactly one callback runs. The serializer runs for a result on the page, and the skipper for
     * one that is only counted.
     *
     * @param key a value unique to the result, or {@code null} to skip the duplicate check. A duplicate runs neither
     *            callback, since a caller that has a key has already read the result
     * @param serializer reads the result and computes its JSON; returning {@code null} leaves the result out of the
     *            response, although it still counts towards the total
     * @param skipper moves past the result without reading it
     * @return {@code true} if more results are wanted; {@code false} once enough have been seen
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
        // The result at the lookahead only proves there are more, and the total leaves it out. A page that starts
        // just short of MAX_COUNT reaches the lookahead, and writing that result would put a row outside the total.
        if (this.counted < this.lookahead && this.counted > this.offset && this.returned < this.limit) {
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
     * Whether enough results have been seen that offering more changes nothing. A caller reading several sources
     * checks this before it starts on the next one.
     *
     * @return {@code true} if no further results are wanted
     */
    public boolean isFull()
    {
        return this.more;
    }

    /**
     * How many more results can still change the response. A caller need not ask a source for more than this many,
     * since the page ignores the rest.
     *
     * @return the number of results still wanted, {@code 0} once the page {@link #isFull() is full}
     */
    public long getRemainingCapacity()
    {
        return Math.max(0, this.lookahead - this.counted);
    }

    /**
     * Writes the summary of the page: the offset and limit, the number of results written, and the total number of
     * matches, which may be approximate. Call it after closing the {@code rows} array.
     *
     * @param requestId the client's {@code req} parameter, echoed back so that it can match the response to its
     *            request or discard an out-of-order one; {@code null} if the request has none, and then not written
     */
    public void writeSummary(@Nullable final String requestId)
    {
        writeSummary(requestId, null);
    }

    /**
     * Writes the summary of a page whose results may not all have been read. The response stays a well-formed
     * document. It holds the rows gathered before the failure, then the usual summary with an {@code error} saying
     * what went wrong and {@code partial} set to {@code true}. A client can tell an incomplete page from a short
     * one.
     *
     * <p>
     * This reports a failure part-way through the results, when the response may already be on its way. A failure
     * before any result is written is answered with {@link JsonResponses#writeError} instead.
     * </p>
     *
     * @param requestId the client's {@code req} parameter, echoed back so that it can match the response to its
     *            request or discard an out-of-order one; {@code null} if the request has none, and then not written
     * @param error what went wrong, in words for the person who will read it; {@code null} for a page read in full
     */
    public void writeSummary(@Nullable final String requestId, @Nullable final String error)
    {
        if (requestId != null) {
            this.json.write("req", requestId);
        }
        this.json.write("offset", this.offset);
        this.json.write("limit", this.limit);
        this.json.write("returnedrows", this.returned);
        // Leaves out the result at the lookahead
        this.json.write("totalrows", this.more ? this.counted - 1 : this.counted);
        this.json.write("totalIsApproximate", this.more);
        if (error != null) {
            this.json.write("error", error);
            this.json.write("partial", true);
        }
    }
}
