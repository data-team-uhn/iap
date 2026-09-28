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
package io.uhndata.iap.errortracking.internal;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.errortracking.models.LoggedErrorsHomepage;
import io.uhndata.iap.utils.summary.AdminSummaryServlet;
import io.uhndata.iap.utils.summary.SummaryUnavailableException;

/**
 * Counts the recorded errors for the console widget: {@code GET /LoggedErrors.adminSummary.json} answers with how
 * many are asking for attention and how many have been recorded altogether.
 *
 * <p>
 * The figures come from the homepage model, which reads the children directly. Both counts are exact and need no
 * index. The model also decides what counts as needing attention.
 * </p>
 *
 * {@snippet lang=json :
 * {
 *   "needingAttention": {"label": "Needing attention", "value": 3, "important": "nonzero"},
 *   "total": {"label": "Recorded in total", "value": 41}
 * }
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = LoggedErrorsHomepage.RESOURCE_TYPE,
    selectors = AdminSummaryServlet.SELECTOR,
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class LoggedErrorsSummaryServlet extends AdminSummaryServlet
{
    private static final long serialVersionUID = 1L;

    @Override
    protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
        throws SummaryUnavailableException
    {
        final LoggedErrorsHomepage errors = request.getResource().adaptTo(LoggedErrorsHomepage.class);
        if (errors == null) {
            throw new SummaryUnavailableException("The recorded errors cannot be read");
        }
        return Json.createObjectBuilder()
            .add("needingAttention", count("Needing attention", errors.getUnacknowledgedErrors().size())
                .add("important", "nonzero"))
            .add("total", count("Recorded in total", errors.getErrors().size()));
    }
}
