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

import java.io.IOException;

import jakarta.json.Json;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * Answers whether mail is being caught, and how much of it, as {@code /CaughtMail.status.json}.
 *
 * <p>
 * <strong>Whether it is on is a question only the service registry can answer.</strong> The configuration says
 * what somebody asked for; this says what is in force, which is not the same thing while a component is
 * settling or if something outranks the catcher in turn. So the answer is the presence of a mail service
 * carrying {@link CaughtMailService#CATCHER_PROPERTY}, not a reading of the configuration.
 * </p>
 *
 * <p>
 * <strong>The count is here rather than left to {@code .paginate.json} deliberately</strong>, against the usual
 * preference for not answering a question the pagination servlet already answers. A dashboard widget wants one
 * request, and the two halves it needs are useless apart: a count with no idea whether catching is on reads as
 * "no mail has been sent", which is the opposite of what an empty mailbox means on an instance that is not
 * catching. The listing itself still goes through {@code .paginate.json}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = "mail/CaughtMailHomepage",
    selectors = "status",
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class CaughtMailStatusServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 8195516947118220627L;

    /**
     * The catcher's own mail service, present only while it is switched on. Dynamic and greedy so that toggling
     * the setting is reflected without this component being restarted; optional because its absence is the
     * answer rather than a reason not to run.
     */
    @Reference(target = "(" + CaughtMailService.CATCHER_PROPERTY + "=true)",
        cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile MailService catcher;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.createObjectBuilder()
            .add("enabled", this.catcher != null)
            .add("total", count(request.getResource()))
            .build().toString());
    }

    /**
     * How many messages have been caught.
     *
     * @param home the folder they are filed in
     * @return the number of messages, counting only those and not the access control policy that shares the
     *         folder with them
     */
    private static int count(final Resource home)
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
