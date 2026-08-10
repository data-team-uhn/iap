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
package io.uhndata.iap.deletion.internal;

import java.io.IOException;
import java.util.Collection;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.SlingJakartaHttpServletResponse;

import io.uhndata.iap.deletion.api.DeletionImpact;
import io.uhndata.iap.deletion.api.ReferrerGroup;
import io.uhndata.iap.deletion.api.Veto;

/**
 * The JSON vocabulary shared by the deletion endpoints. Every response carries {@code status.code} and, when there
 * is something to say, {@code status.message}, mirroring the default POST servlet's error format, plus a short
 * machine-readable {@code status} word and operation-specific details.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class JsonResponses
{
    private JsonResponses()
    {
        // Utility class
    }

    /**
     * Start a response body.
     *
     * @param code the HTTP status code, repeated in the body
     * @param status a short machine-readable outcome word, e.g. {@code archived}
     * @return a builder to complete
     */
    static JsonObjectBuilder body(final int code, final String status)
    {
        return Json.createObjectBuilder().add("status.code", code).add("status", status);
    }

    /**
     * Send a response.
     *
     * @param response the response to write to
     * @param code the HTTP status code
     * @param body the complete body
     * @throws IOException if the response cannot be written
     */
    static void send(final SlingJakartaHttpServletResponse response, final int code, final JsonObjectBuilder body)
        throws IOException
    {
        response.setStatus(code);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(body.build().toString());
    }

    /**
     * Send a minimal response with just a status word and a message.
     *
     * @param response the response to write to
     * @param code the HTTP status code
     * @param status a short machine-readable outcome word, e.g. {@code denied}
     * @param message the human-readable explanation, skipped when empty
     * @throws IOException if the response cannot be written
     */
    static void send(final SlingJakartaHttpServletResponse response, final int code, final String status,
        final String message) throws IOException
    {
        final JsonObjectBuilder result = body(code, status);
        if (!message.isEmpty()) {
            result.add("status.message", message);
        }
        send(response, code, result);
    }

    /**
     * The details of a blocked or examined deletion: what would go, and what stands in the way.
     *
     * @param body the builder to add to
     * @param impact the impact to describe
     */
    static void describeImpact(final JsonObjectBuilder body, final DeletionImpact impact)
    {
        body.add("items", paths(impact.getItemPaths()));
        body.add("removedLinks", paths(impact.getRemovedLinkPaths()));
        if (!impact.getVetoes().isEmpty()) {
            body.add("vetoes", vetoes(impact.getVetoes()));
        }
        if (!impact.getReferrers().isEmpty() || impact.getInaccessibleReferrerCount() > 0) {
            body.add("referrers", referrers(impact.getReferrers()));
            body.add("inaccessibleReferrers", impact.getInaccessibleReferrerCount());
        }
        if (!impact.getSummary().isEmpty()) {
            body.add("status.message", impact.getSummary());
        }
    }

    /**
     * A list of paths as a JSON array.
     *
     * @param items the paths to list
     * @return an array builder
     */
    static JsonArrayBuilder paths(final Collection<String> items)
    {
        final JsonArrayBuilder result = Json.createArrayBuilder();
        items.forEach(result::add);
        return result;
    }

    /**
     * The objections raised by deletion guards as a JSON array.
     *
     * @param vetoes the objections
     * @return an array builder
     */
    static JsonArrayBuilder vetoes(final Collection<Veto> vetoes)
    {
        final JsonArrayBuilder result = Json.createArrayBuilder();
        vetoes.stream()
            .map(veto -> Json.createObjectBuilder()
                .add("vetoer", veto.getVetoerName())
                .add("path", veto.getPath())
                .add("reason", veto.getReason()))
            .forEach(result::add);
        return result;
    }

    private static JsonArrayBuilder referrers(final Collection<ReferrerGroup> groups)
    {
        final JsonArrayBuilder result = Json.createArrayBuilder();
        groups.stream()
            .map(group -> Json.createObjectBuilder()
                .add("type", group.getNodeType())
                .add("label", group.getLabel())
                .add("count", group.getCount())
                .add("names", paths(group.getNames())))
            .forEach(result::add);
        return result;
    }
}
