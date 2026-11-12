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
package io.uhndata.iap.emailcatcher.internal;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.utils.summary.AdminSummaryServlet;

/**
 * Answers whether mail is being caught, and how many messages have been, at {@code /CaughtMail.adminSummary.json}.
 * {@code enabled} is whether the catcher's mail service is registered, not what its setting asks for.
 *
 * {@snippet lang=json :
 * {
 *   "enabled": {"label": "Catching mail", "value": true},
 *   "total": {"label": "Caught so far", "value": 12}
 * }
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = "mail/CaughtMailHomepage",
    selectors = AdminSummaryServlet.SUMMARY_SELECTOR,
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class CaughtMailSummaryServlet extends AdminSummaryServlet
{
    private static final long serialVersionUID = 8195516947118220627L;

    /**
     * The catcher's mail service; {@code null} while the catcher is off. Dynamic and greedy, so that a switch
     * takes effect without restarting this component.
     */
    @Reference(target = "(" + CaughtMailService.CATCHER_PROPERTY + "=true)",
        cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile MailService catcher;

    @Override
    protected JsonObjectBuilder summarize(final SlingJakartaHttpServletRequest request)
    {
        return Json.createObjectBuilder()
            .add("enabled", state("Catching mail", this.catcher != null))
            .add("total", count("Caught so far", countMessages(request.getResource())));
    }

    /**
     * How many messages have been caught.
     *
     * @param home the {@code /CaughtMail} resource
     * @return the number of {@code mail:CaughtMessage} children, which leaves out the folder's access control
     *         policy
     */
    private static int countMessages(final Resource home)
    {
        int messages = 0;
        for (final Resource child : home.getChildren()) {
            if (CaughtMailService.MESSAGE_TYPE.equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                messages++;
            }
        }
        return messages;
    }
}
