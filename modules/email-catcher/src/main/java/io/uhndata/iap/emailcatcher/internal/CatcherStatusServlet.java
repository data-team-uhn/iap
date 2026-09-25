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
 * Answers whether mail is being caught, to anyone who asks, as
 * {@code /libs/iap/mail-catcher.catching.json}.
 *
 * <p>
 * <strong>This is the only thing about the catcher that is not administrators-only, and it is
 * deliberately one boolean.</strong> An instance that catches mail delivers none, and the person who
 * most needs telling is whoever is waiting for a message — a password reset above all, which means
 * somebody on the sign-in page with no session at all. {@code /libs} is exempt from
 * {@code sling.auth.requirements}, so this answers before sign-in; what was actually caught stays
 * behind {@code /CaughtMail}, which administrators alone can read.
 * </p>
 *
 * <p>
 * <strong>Read from the service registry, not the configuration</strong>, for the same reason
 * {@link CaughtMailSummaryServlet} does: the configuration says what was asked for and this says what
 * is in force. A banner that showed the asked-for state would be wrong for as long as a component
 * took to settle, and wrong permanently if anything ever outranked the catcher in turn.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = "mail/CatcherStatus",
    selectors = "catching",
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class CatcherStatusServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 5471821096339250312L;

    /**
     * The catcher's own mail service, present only while it is switched on. Dynamic and greedy so that
     * toggling the setting is reflected without this component being restarted; optional because its
     * absence is the answer rather than a reason not to run.
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
            .add("catching", this.catcher != null)
            .build().toString());
    }
}
