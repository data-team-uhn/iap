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
package io.uhndata.iap.search.internal;

import java.io.IOException;
import java.util.function.Function;

import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.stream.JsonGenerator;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Writes a page of results as JSON: an object holding the page in its {@code rows} array, followed by the
 * pagination summary.
 *
 * <p>
 * The results are read while the response is written, and reading them can fail after part of it has gone out.
 * Such a failure ends the rows and is reported in the summary, and the response stays a well-formed document.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ResultsResponse
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ResultsResponse.class);

    /** What the results answer, as the log messages name it. */
    private final String purpose;

    /**
     * Creates a writer for one kind of listing.
     *
     * @param purpose what the results answer, as the log messages name it, such as {@code a search}
     */
    ResultsResponse(final String purpose)
    {
        this.purpose = purpose;
    }

    /**
     * Writes the requested page of results, then the pagination summary.
     *
     * @param request the current request, giving the page and the {@code req} identifier to echo back
     * @param response the HTTP response to write into
     * @param results offers the results to the page
     * @throws IOException if writing the response fails
     */
    // A page of results is content, streamed as it is read
    @SuppressWarnings("checkstyle:directResponseWriter")
    void write(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response,
        final Results results) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Closing the generator closes the writer too
        try (JsonGenerator json = Json.createGenerator(response.getWriter())) {
            json.writeStartObject();
            json.writeStartArray("rows");
            final PaginatedJsonResponse page = PaginatedJsonResponse.forRequest(json, request);
            final String error = offer(results, page);
            json.writeEnd();
            page.writeSummary(request.getParameter("req"), error);
            json.writeEnd().flush();
        }
    }

    /**
     * Offers the results to the page, and stops at the first failure to read them.
     *
     * @param results offers the results to the page
     * @param page the paginator for the requested page, positioned inside the {@code rows} array
     * @return the error to report in the summary; {@code null} if every result was read
     */
    private String offer(final Results results, final PaginatedJsonResponse page)
    {
        try {
            results.offerTo(page);
            return null;
        } catch (final RepositoryException | RuntimeException e) {
            // Unchecked too: Oak reports a read or memory limit reached with an unchecked exception
            LOGGER.warn("Failed to read the results of {}: {}", this.purpose, e.getMessage(), e);
            return "Failed to read all the results";
        }
    }

    /**
     * Serializes nodes for the page, with the extra serialization selectors the request asks for.
     *
     * @param request the current request, giving the resource resolver and the {@code resourceSelectors}
     * @return a function from the path of a node to the node serialized; it returns {@code null} if the node cannot
     *         be serialized, and then the node is left out of the page but still counted
     */
    Function<String, JsonObject> nodeSerializer(final SlingJakartaHttpServletRequest request)
    {
        final String selectors = PaginatedJsonResponse.getResourceSelectors(request);
        final ResourceResolver resolver = request.getResourceResolver();
        return path -> serialize(resolver, path, selectors);
    }

    /**
     * Serializes one node.
     *
     * @param resolver the current resource resolver
     * @param path the path of the node to serialize
     * @param selectors the extra serialization selectors the client asked for, such as {@code .deep}; empty for none
     * @return the serialized node; {@code null} if it cannot be serialized
     */
    private JsonObject serialize(final ResourceResolver resolver, final String path, final String selectors)
    {
        try {
            return resolver.resolve(path + selectors).adaptTo(JsonObject.class);
        } catch (final RuntimeException e) {
            LOGGER.warn("Failed to serialize {} for {}: {}", path, this.purpose, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Offers the results of one request to the page.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Results
    {
        /**
         * Offers results to the page until it wants no more or they run out.
         *
         * @param page the paginator for the requested page, positioned inside the {@code rows} array
         * @throws RepositoryException if reading the results fails
         */
        void offerTo(PaginatedJsonResponse page) throws RepositoryException;
    }
}
