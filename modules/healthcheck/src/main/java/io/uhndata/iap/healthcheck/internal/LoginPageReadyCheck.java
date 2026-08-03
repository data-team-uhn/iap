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
 * A health check reporting whether the login page can actually be rendered. Merely reaching the target start level is
 * not enough for the system to be usable: the page scripts are still being loaded into the repository, and the
 * scripting engine is still wiring itself up, so early requests can get raw errors instead of a page. Tagged
 * {@code systemalive}, so the {@code StartupGateFilter} keeps serving the "starting up" stub, which retries
 * automatically, until a rendered login page is what visitors will get. The internal rendering goes through
 * {@link SlingRequestProcessor}, not over HTTP, so it is not blocked by that same filter.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HealthCheck.class, property = {
    HealthCheck.NAME + "=" + LoginPageReadyCheck.NAME,
    HealthCheck.TAGS + "=systemalive"
})
public class LoginPageReadyCheck implements HealthCheck
{
    /**
     * The check name. The startup gate ({@code StartupGateFilter} in the startup-customization module) requires a
     * check with exactly this name to be present and passing, so it must not be renamed without updating the filter.
     */
    public static final String NAME = "Login Page Ready Check";

    /**
     * The path of the login page, the one page that must work before the platform presents itself as up. Deliberately
     * extensionless, because that is what visitors actually hit: the authentication handler redirects them to
     * {@code /login?resource=...}, which renders through the extensionless scripts, a superset of the {@code .html}
     * ones.
     */
    private static final String LOGIN_PAGE = "/login";

    private final ResourceResolverFactory resolverFactory;

    private final SlingRequestProcessor slingRequestProcessor;

    /**
     * Constructor injection, both for OSGi and for the tests.
     *
     * @param resolverFactory provides the service resource resolver the internal rendering runs with
     * @param slingRequestProcessor renders the page internally, without going through HTTP
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
