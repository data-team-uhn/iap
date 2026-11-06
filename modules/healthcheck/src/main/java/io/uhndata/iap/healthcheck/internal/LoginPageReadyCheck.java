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
package io.uhndata.iap.healthcheck.internal;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.internalrequests.JakartaSlingInternalRequest;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Passes once the login page renders. While IAP starts, a request can get a raw error instead of a page. The page's
 * scripts may not be in the repository yet, or Sling may not be able to compile them yet. The check is tagged
 * {@code systemalive}, so the startup gate in the startup-customization module holds requests until it passes. It
 * renders the page through Sling's {@link SlingRequestProcessor}, not over HTTP, so the gate does not hold it back.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HealthCheck.class, property = {
    HealthCheck.NAME + "=" + LoginPageReadyCheck.CHECK_NAME,
    HealthCheck.TAGS + "=systemalive"
})
public class LoginPageReadyCheck implements HealthCheck
{
    /**
     * The name of this check. {@code StartupGateFilter}, in the startup-customization module, waits for a check with
     * exactly this name, so renaming it means changing the filter too.
     */
    public static final String CHECK_NAME = "Login Page Ready Check";

    /**
     * The path of the login page, without an extension. Sling's form authentication handler sends visitors to
     * {@code /login?resource=...}, which has none. The script for that rendering includes the {@code .html} one, so a
     * 200 here covers both.
     */
    private static final String LOGIN_PAGE = "/login";

    private final ResourceResolverFactory resolverFactory;

    private final SlingRequestProcessor slingRequestProcessor;

    /**
     * Creates the check with the services it renders the login page with.
     *
     * @param resolverFactory opens the session the page is rendered with, as the {@code healthcheck} subservice
     * @param slingRequestProcessor renders the page inside Sling, without an HTTP request
     */
    @Activate
    public LoginPageReadyCheck(@Reference final ResourceResolverFactory resolverFactory,
        @Reference final SlingRequestProcessor slingRequestProcessor)
    {
        this.resolverFactory = resolverFactory;
        this.slingRequestProcessor = slingRequestProcessor;
    }

    @Override
    public Result execute()
    {
        try (ResourceResolver resolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "healthcheck"))) {
            final int status = new JakartaSlingInternalRequest(resolver, this.slingRequestProcessor, LOGIN_PAGE)
                .execute()
                .getStatus();
            return status == HttpServletResponse.SC_OK
                ? new Result(Result.Status.OK, "The login page renders")
                : new Result(Result.Status.TEMPORARILY_UNAVAILABLE, "The login page responds with " + status);
        } catch (final Exception e) {
            return new Result(Result.Status.TEMPORARILY_UNAVAILABLE,
                "The login page cannot be rendered yet: " + e.getMessage(), e);
        }
    }
}
