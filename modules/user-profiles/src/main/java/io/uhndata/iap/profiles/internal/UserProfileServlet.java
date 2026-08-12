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
package io.uhndata.iap.profiles.internal;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.api.Requester;
import io.uhndata.iap.profiles.api.UpdateOutcome;
import io.uhndata.iap.profiles.api.UserProfileService;
import io.uhndata.iap.utils.UserIds;

/**
 * Serves and accepts changes to profiles at {@code /system/iap/profile}, with the account named by the suffix and the
 * requester's own when there is none: {@code GET /system/iap/profile.json} is "mine",
 * {@code GET /system/iap/profile/jdoe.json} is somebody else's.
 *
 * <p>
 * Bound to a path rather than to the {@code sling/user} resource type, which would otherwise be the natural choice.
 * Resolving another account's authorizable resource needs read access to their home node, and user homes are not
 * readable by everyone; the resource-type binding would therefore work for one's own profile and quietly 404 for
 * anybody else's.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths("/system/iap/profile")
public class UserProfileServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileServlet.class);

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    @Reference
    private transient UserProfileService profiles;

    /** Constructor for OSGi, which injects the profile service into the field above. */
    public UserProfileServlet()
    {
        // Everything is injected
    }

    /**
     * Constructor taking the service to serve from, so that tests need no OSGi container.
     *
     * @param profiles reads and changes profiles
     */
    UserProfileServlet(@NotNull final UserProfileService profiles)
    {
        this.profiles = profiles;
    }

    @Override
    protected void doGet(@NotNull final SlingJakartaHttpServletRequest request,
        @NotNull final SlingJakartaHttpServletResponse response) throws IOException
    {
        final Requester requester = requester(request);
        final Optional<ProfileProjection> profile = this.profiles.read(target(request, requester), requester);
        if (profile.isEmpty()) {
            response.sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, SlingJakartaHttpServletResponse.SC_OK, profile.get().asJson());
    }

    @Override
    protected void doPost(@NotNull final SlingJakartaHttpServletRequest request,
        @NotNull final SlingJakartaHttpServletResponse response) throws IOException
    {
        final Requester requester = requester(request);
        final Optional<UpdateOutcome> outcome =
            this.profiles.update(target(request, requester), requester, request.getParameterMap());
        if (outcome.isEmpty()) {
            response.sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, status(outcome.get()), outcome.get().toJson().toString());
    }

    /**
     * The status that goes with an outcome: whether the person may not act here at all, or asked for something that
     * cannot be recorded, are different answers and deserve different codes.
     *
     * @param outcome what came of the attempt
     * @return an HTTP status
     */
    private static int status(@NotNull final UpdateOutcome outcome)
    {
        if (outcome.isForbidden()) {
            return SlingJakartaHttpServletResponse.SC_FORBIDDEN;
        }
        return outcome.isRefused() ? SlingJakartaHttpServletResponse.SC_BAD_REQUEST
            : SlingJakartaHttpServletResponse.SC_OK;
    }

    /**
     * The account being asked about: the one named by the suffix, or the requester's own.
     *
     * @param request the request being served
     * @param requester who is asking
     * @return an authorizable id
     */
    @NotNull
    private static String target(@NotNull final SlingJakartaHttpServletRequest request,
        @NotNull final Requester requester)
    {
        final String suffix = request.getRequestPathInfo().getSuffix();
        if (suffix == null || suffix.isBlank() || "/".equals(suffix)) {
            return requester.getId();
        }
        return suffix.startsWith("/") ? suffix.substring(1) : suffix;
    }

    /**
     * Works out who is asking, and every principal they hold. Identity provider roles are dynamic principals that
     * never become groups, so they can only be seen among the principals the session is bound to, which is also where
     * the local groups are -- which is what lets the two be used interchangeably.
     *
     * @param request the request being served
     * @return the requester, holding at least their own name when the principals cannot be read
     */
    @NotNull
    private static Requester requester(@NotNull final SlingJakartaHttpServletRequest request)
    {
        final ResourceResolver resolver = request.getResourceResolver();
        // The canonical id rather than the login as typed, since that is what the account being looked at answers
        // with. Anonymous access is refused before a request reaches here, so there is always somebody; the fallback
        // only keeps a resolver that will not say who it is from turning into a failure with no explanation
        final String id = Objects.requireNonNullElse(UserIds.canonical(resolver), "");
        final Set<String> principals = new TreeSet<>();
        principals.add(id);
        final Session session = resolver.adaptTo(Session.class);
        if (session != null) {
            collectPrincipals(session, id, principals);
        }
        return new Requester(id, principals);
    }

    /**
     * Puts every principal the session is bound to into the given set, leaving it as it is if the repository will not
     * say.
     *
     * @param session the requester's own session
     * @param id the account the request is made as
     * @param principals the set being filled
     */
    private static void collectPrincipals(@NotNull final Session session, @NotNull final String id,
        @NotNull final Set<String> principals)
    {
        try {
            principals.addAll(UserIds.principalsOf(session));
        } catch (final RepositoryException e) {
            // Failing closed: without the group principals the requester is treated as holding nothing but their own
            // name, so they can still read and change their own profile and nothing else. Recorded all the same,
            // since an administrator who cannot act on anybody else's profile will want to know why.
            LOGGER.warn("Cannot read the principals of {}: {}", id, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(UserProfileServlet.class, "requester").actingFor(id));
        }
    }

    /**
     * Writes one JSON answer.
     *
     * @param response the response being written
     * @param status the HTTP status
     * @param body what to say
     * @throws IOException if the response cannot be written
     */
    // Not JsonResponses: a profile is content, and the outcome of a change carries a reason per field where an error
    // answer has room for one
    @SuppressWarnings("checkstyle:directResponseWriter")
    private static void write(@NotNull final SlingJakartaHttpServletResponse response, final int status,
        @NotNull final String body) throws IOException
    {
        response.setStatus(status);
        response.setContentType(JSON_CONTENT_TYPE);
        response.getWriter().write(body);
    }
}
