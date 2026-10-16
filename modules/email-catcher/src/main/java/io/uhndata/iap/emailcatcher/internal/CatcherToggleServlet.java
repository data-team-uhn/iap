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
 * It writes the setting the Felix console offers, through {@link ConfigurationAdmin}. The component has
 * no {@code @Modified} method, so an update restarts it, and its mail service registration follows.
 * </p>
 *
 * <p>
 * Only administrators can resolve {@code /CaughtMail}, and the caller is checked again here, so the
 * guard does not rest on how the servlet is bound. The repository superuser passes without the group.
 * They bypass access control and can write this setting from the Felix console anyway. On a fresh
 * instance theirs is the only account.
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
    static final String ADMINISTRATORS_GROUP = "iap-administrators";

    /** The setting written, on the catcher's own component. */
    static final String CATCHER_PID = "io.uhndata.iap.emailcatcher.internal.CaughtMailService";

    /** The property within it. */
    static final String ENABLED_PROPERTY = "enabled";

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

        final String requested = request.getParameter(ENABLED_PROPERTY);
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
            // What was asked for. The component takes a moment to restart, and
            // /libs/iap/mail-catcher.catching.json says what is in force.
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
        final Configuration configuration = this.configurationAdmin.getConfiguration(CATCHER_PID, null);
        final Dictionary<String, Object> properties = configuration.getProperties() == null
            ? new Hashtable<>() : configuration.getProperties();
        properties.put(ENABLED_PROPERTY, enabled);
        configuration.update(properties);
    }

    /**
     * Whether the caller may do this.
     *
     * @param request the request being served
     * @return {@code true} when the caller belongs to {@link #ADMINISTRATORS_GROUP}
     */
    private boolean isAdministrator(final SlingJakartaHttpServletRequest request)
    {
        final ResourceResolver resolver = request.getResourceResolver();
        final String caller = UserIds.canonical(resolver);
        if (caller == null || caller.isBlank()) {
            return false;
        }
        return isSuperuser(resolver, caller)
            || this.principals.isOneOf(caller, List.of(ADMINISTRATORS_GROUP), resolver);
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
            // Not recorded as an error: the group check below asks the same question.
            LOGGER.debug("Could not ask the user store about {}", caller, e);
            return false;
        }
    }
}
