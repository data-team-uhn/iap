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
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.utils.PaginatedJsonResponse;
import io.uhndata.iap.utils.UserIds;

/**
 * Switches the email catcher on and off, as {@code POST /CaughtMail.catching.json} with
 * {@code enabled=true|false}.
 *
 * <p>
 * <strong>It writes the same setting the Felix console offers</strong>, through
 * {@link ConfigurationAdmin}, rather than keeping a second switch of its own. The component has no
 * {@code @Modified} method, so updating the configuration deactivates and reactivates it, the mail
 * service registration follows, and dynamic consumers rebind with no restart. One switch, whichever
 * way it is reached.
 * </p>
 *
 * <p>
 * <strong>A restart returns whatever the deployment ships, where it ships one.</strong> Feature
 * configuration is applied at every startup, so the test and demo aggregates — which carry
 * {@code enabled: true} — come back catching however they were left. The core aggregates carry no
 * configuration for this PID at all, so there a change made here survives the restart instead. That
 * asymmetry is the right way round: the environments that declare a state get it back, and an
 * administrator who switched a production instance over keeps it switched until they say otherwise.
 * </p>
 *
 * <p>
 * <strong>Gated twice, deliberately.</strong> {@code /CaughtMail} is readable by administrators
 * alone, so resolving this resource is already a check — but a resource binding has twice been
 * relied on here and turned out to protect nothing, so who is asking is also checked outright. The
 * repository superuser passes that second check without belonging to the group, on the same
 * reasoning as a workflow's performer check: they bypass access control anyway and can write this
 * very setting from the Felix console, so refusing them here would guard nothing while locking out
 * the only account a fresh instance has.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = "mail/CaughtMailHomepage",
    selectors = "catching",
    extensions = "json",
    methods = { HttpConstants.METHOD_POST })
public class CatcherToggleServlet extends SlingJakartaAllMethodsServlet
{
    /** The group that may switch mail catching on and off. */
    static final String ADMINISTRATORS = "iap-administrators";

    /** The setting written, on the catcher's own component. */
    static final String PID = "io.uhndata.iap.emailcatcher.internal.CaughtMailService";

    /** The property within it. */
    static final String ENABLED = "enabled";

    private static final Logger LOGGER = LoggerFactory.getLogger(CatcherToggleServlet.class);

    private static final long serialVersionUID = -2934180267104417365L;

    @Reference
    private transient ConfigurationAdmin configurationAdmin;

    @Reference
    private transient PrincipalService principals;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json;charset=UTF-8");

        if (!isAdministrator(request)) {
            PaginatedJsonResponse.writeError(response, 403,
                "Only an administrator may switch mail catching on or off");
            return;
        }

        final String requested = request.getParameter(ENABLED);
        if (!"true".equals(requested) && !"false".equals(requested)) {
            PaginatedJsonResponse.writeError(response, 400, "'enabled' must be true or false");
            return;
        }

        final boolean enabled = Boolean.parseBoolean(requested);
        try {
            apply(enabled);
        } catch (final IOException e) {
            LOGGER.warn("The email catcher setting could not be written", e);
            ErrorLogger.logError(e, ErrorContext.of(CatcherToggleServlet.class, "doPost"));
            PaginatedJsonResponse.writeError(response, 500, "The setting could not be written");
            return;
        }

        LOGGER.info("Mail catching was switched {} by {}", enabled ? "on" : "off",
            UserIds.canonical(request.getResourceResolver()));
        response.getWriter().write(Json.createObjectBuilder()
            // What was asked for, not what is in force: the component takes a moment to settle, and
            // reading the registry here would report the state this request has just replaced.
            // /libs/iap/mail-catcher.catching.json answers the other question.
            .add("requested", enabled)
            .build().toString());
    }

    /**
     * Write the setting, leaving every other property of the configuration alone.
     *
     * @param enabled whether mail should be caught
     * @throws IOException when the configuration cannot be read or written
     */
    private void apply(final boolean enabled) throws IOException
    {
        final Configuration configuration = this.configurationAdmin.getConfiguration(PID, null);
        final Dictionary<String, Object> properties = configuration.getProperties() == null
            ? new Hashtable<>() : configuration.getProperties();
        properties.put(ENABLED, enabled);
        configuration.update(properties);
    }

    /**
     * Whether the caller may do this.
     *
     * @param request the request being served
     * @return {@code true} when the caller belongs to {@link #ADMINISTRATORS}
     */
    private boolean isAdministrator(final SlingJakartaHttpServletRequest request)
    {
        final ResourceResolver resolver = request.getResourceResolver();
        final String caller = UserIds.canonical(resolver);
        if (caller == null || caller.isBlank()) {
            return false;
        }
        return isSuperuser(resolver, caller)
            || this.principals.isOneOf(caller, List.of(ADMINISTRATORS), resolver);
    }

    /**
     * Whether the caller is the repository superuser.
     *
     * @param resolver the caller's own resolver
     * @param caller the canonical user id
     * @return {@code true} when the user store reports them an administrator
     */
    private static boolean isSuperuser(final ResourceResolver resolver, final String caller)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (!(session instanceof JackrabbitSession jackrabbit)) {
            return false;
        }
        try {
            final Authorizable authorizable = jackrabbit.getUserManager().getAuthorizable(caller);
            return authorizable instanceof User user && user.isAdmin();
        } catch (final RepositoryException e) {
            // Not recorded as an error: the group check below answers the same question, and a user
            // store that cannot be read will say so there in its own terms.
            LOGGER.debug("Could not ask the user store about {}", caller, e);
            return false;
        }
    }
}
