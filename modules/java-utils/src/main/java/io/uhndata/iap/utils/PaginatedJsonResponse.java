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
 * The response conventions shared by the endpoints that return a page of results as JSON. This class reads
 * {@code offset} and {@code limit} from the request, counts the matches, and writes the summary that follows the
 * results.
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
 * The caller writes the {@code rows} array itself and {@link #offer offers} each candidate result to this class, which
 * decides whether it belongs on the requested page, serializes it if so, and keeps the counts. Counting stops at the
 * end of the {@value #LOOKAHEAD_PAGES}-page batch holding the requested page. A total that stopped there is reported
 * with {@code totalIsApproximate} set.
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
 *         // Read the result here, not inside the serializer: the serializer only runs for the results that end up
 *         // in the response, so advancing the iterator from it would leave the loop spinning on every other one
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
    /** The number of results returned when the request doesn't ask for a specific {@code limit}: {@value}. */
    public static final long DEFAULT_LIMIT = 10;

    /** The number of results returned at most, no matter how large a {@code limit} the request asks for: {@value}. */
    public static final long MAX_LIMIT = 1000;

    /**
     * The size, in pages, of the batch counted before the total is called approximate: {@value}. Counting iterates
     * the query results without serializing them. The horizon is deep enough to keep the total exact for all but the
     * largest collections.
     */
    public static final long LOOKAHEAD_PAGES = 100;

    /**
     * The number of results a single request ever counts, whatever it asks for: {@value}. {@link #MAX_LIMIT} caps the
     * {@code limit}, but the {@code offset} also comes from the client and nothing else bounds it. Without this
     * ceiling one request could walk the whole repository, holding a key per match. It caps the counting batch too:
     * {@value #LOOKAHEAD_PAGES} pages of {@link #MAX_LIMIT} results is a hundred thousand.
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
        // Count to the end of the batch of pages holding the requested page, plus one result to tell whether the
        // total is exact. A limit of 0 asks for a count only and counts as far as any request may; it also keeps
        // pageSize non-zero for the division below.
        final long batch = limit > 0 ? Math.min(limit, MAX_LIMIT) : MAX_LIMIT;
        final long pageSize = LOOKAHEAD_PAGES * batch;
        // The offset is clamped here rather than in the field. The summary still reports what was asked for.
        // Clamping both terms keeps the sum in range: forPage passes its arguments through untouched, and only small
        // positive values here stop the lookahead wrapping negative.
        final long wanted = Math.max(0, Math.min(offset, MAX_COUNT)) + batch;
        this.lookahead = Math.min(MAX_COUNT, ((wanted + pageSize - 1) / pageSize) * pageSize) + 1;
    }

    /**
     * Starts the page the client asked for, from the {@code offset} and {@code limit} request parameters, corrected
     * to sane values.
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
     * Starts an explicitly sized page. The caller is responsible for the values being sane. Prefer
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
     * Writes an error response as a small JSON object, replacing the whole response body. Use it for a request that
     * failed before any result was written. Once rows are going out, the failure belongs in the summary instead, via
     * {@link #writeSummary(String, String)}.
     *
     * <p>
     * A committed response cannot be turned into an error. Its status is on the wire and its body is partly sent, and
     * a second JSON object appended to a body that already holds one is not something a client can parse. Such a call
     * is ignored.
     * </p>
     *
     * @param response the HTTP response
     * @param status the HTTP status code to send
     * @param message the error message to include in the response
     * @throws IOException if writing the response fails
     */
    public static void writeError(@NotNull final SlingJakartaHttpServletResponse response, final int status,
        @Nullable final String message) throws IOException
    {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.write("error", Objects.requireNonNullElse(message, "Invalid request"));
            json.writeEnd().flush();
        }
    }

    /**
     * Converts a request parameter, which may be missing or invalid, into a {@code long}, falling back to a default
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
     * Offers one result to the page. Results before the requested offset and past the requested limit are only
     * counted. The serializer runs for the results that end up in the response.
     *
     * @param key a value uniquely identifying the result. A result reached twice, as a query with a join routinely
     *            does, is counted and returned once. {@code null} skips the duplicate check, for a source that cannot
     *            produce the same result twice
     * @param serializer computes the JSON for the result; returning {@code null} leaves the result out of the
     *            response, although it still counts towards the total
     * @return {@code true} if more results are wanted, {@code false} once enough have been seen and the caller should
     *         stop; the inverse of a subsequent {@link #isFull()}
     */
    public boolean offer(@Nullable final String key, @NotNull final Supplier<JsonObject> serializer)
    {
        return offer(key, serializer, () -> {
            // A caller with no skipper has already moved past the result
        });
    }

    /**
     * Offers one result to the page, for a source that only moves forward when it is told to. Exactly one of the two
     * callbacks is invoked for every result that isn't a duplicate: the serializer for the results that go into the
     * response, the skipper for the ones that are only counted.
     *
     * @param key a value uniquely identifying the result, or {@code null} to skip the duplicate check. The skipper is
     *            not called for a duplicate: a caller that has a key has already read the result
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
        // The result at the lookahead only proves there are more, and the summary leaves it out of the total. For a
        // page starting close enough to MAX_COUNT the lookahead lands inside the requested page, where writing it
        // would put a row in the response that the reported total does not cover.
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
     * Whether enough results have been seen for offering more to change nothing. A caller pulling from several
     * sources checks this before starting on the next one.
     *
     * @return {@code true} if no further results are wanted
     */
    public boolean isFull()
    {
        return this.more;
    }

    /**
     * How many more results can still change the response. A caller asks a source for no more than this many;
     * anything past them is discarded.
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
     * @param requestId the opaque {@code req} request parameter, echoed back for the client to match the response to
     *            its request or discard an out-of-order one; not written when {@code null}
     */
    public void writeSummary(@Nullable final String requestId)
    {
        writeSummary(requestId, null);
    }

    /**
     * Writes the summary of a page whose results could not all be read. The response stays a well-formed document:
     * the rows gathered before the failure, then the usual summary, with an {@code error} describing what went wrong
     * and {@code partial} set. A client can tell an incomplete page from a short one.
     *
     * <p>
     * This is how a failure part-way through the results is reported. By then the response may already be on the
     * wire. A failure before any result is written is an ordinary {@link #writeError} response.
     * </p>
     *
     * @param requestId the opaque {@code req} request parameter, echoed back for the client to match the response to
     *            its request or discard an out-of-order one; not written when {@code null}
     * @param error a description of the failure, or {@code null} for a page that was read in full
     */
    public void writeSummary(@Nullable final String requestId, @Nullable final String error)
    {
        if (requestId != null) {
            this.json.write("req", requestId);
        }
        this.json.write("offset", this.offset);
        this.json.write("limit", this.limit);
        this.json.write("returnedrows", this.returned);
        // The result at the lookahead proves there are more and is not itself part of the total
        this.json.write("totalrows", this.more ? this.counted - 1 : this.counted);
        this.json.write("totalIsApproximate", this.more);
        if (error != null) {
            this.json.write("error", error);
            this.json.write("partial", true);
        }
    }
}
