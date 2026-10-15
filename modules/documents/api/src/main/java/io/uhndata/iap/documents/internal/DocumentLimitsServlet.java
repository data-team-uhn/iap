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
package io.uhndata.iap.documents.internal;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.documents.api.DocumentLimits;

/**
 * Serves the document limits at {@code /system/documents/limits}, so the browser checks an upload against the
 * server's own numbers instead of keeping a copy of them:
 * {@code {"maxFileSize": bytes, "maxPdfPages": pages, "maxUnzippedSize": bytes}}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(value = DocumentLimitsServlet.PATH)
public class DocumentLimitsServlet extends SlingJakartaSafeMethodsServlet
{
    /** The path this endpoint is served at. */
    static final String PATH = "/system/documents/limits";

    private static final long serialVersionUID = 6113853385296424129L;

    @Reference
    private transient DocumentLimits limits;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        JsonResponse.write(response, HttpServletResponse.SC_OK, Json.createObjectBuilder()
            .add("maxFileSize", this.limits.getMaxFileSize())
            .add("maxPdfPages", this.limits.getMaxPdfPages())
            .add("maxUnzippedSize", this.limits.getMaxUnzippedSize())
            .build());
    }
}
