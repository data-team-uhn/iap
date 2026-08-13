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
import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.principal.PrincipalIterator;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
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

import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.api.Requester;
import io.uhndata.iap.profiles.api.UpdateOutcome;
import io.uhndata.iap.profiles.api.UserProfileService;

/**
 * Serves and accepts changes to profiles at {@code /system/iap/profile}, with the account named by the suffix and the
 * requester's own when there is none: {@code GET /system/iap/profile.json} is "mine",
 * {@code GET /system/iap/profile.json/jdoe} is somebody else's. The account goes after the extension, not before it:
 * Sling stops reading selectors and extension at the first slash, so everything in
 * {@code /system/iap/profile/jdoe.json} past the servlet's own path is the suffix, and the account would be looked up
 * as {@code jdoe.json}. Left as the raw suffix rather than stripped of a trailing extension, since an authorizable id
 * containing a dot is perfectly ordinary.
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

    private static final String JSON = "application/json;charset=UTF-8";

    /** The parameter a browser form sends to say how it encoded the rest of them. */
    private static final String CHARSET = "_charset_";

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
            this.profiles.update(target(request, requester), requester, asked(request));
        if (outcome.isEmpty()) {
            response.sendError(SlingJakartaHttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(response, status(outcome.get()), outcome.get().toJson().toString());
    }

    /**
     * The fields a request asks to change: every posted parameter except the ones that are Sling's rather than
     * anybody's. A request naming a field this instance does not record is refused whole, deliberately, so the
     * {@code _charset_} a browser form sends and the {@code :}-prefixed parameters the Sling POST servlet reserves
     * would otherwise turn every save made from a form into a refusal.
     *
     * @param request the request being served
     * @return the new values, keyed by field name
     */
    @NotNull
    private static Map<String, String[]> asked(@NotNull final SlingJakartaHttpServletRequest request)
    {
        return request.getParameterMap().entrySet().stream()
            .filter(parameter -> !reserved(parameter.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Whether a parameter name is one of the request's own rather than a field name.
     *
     * @param name the parameter name
     * @return {@code true} for a parameter the profile API should not read as a field
     */
    private static boolean reserved(@NotNull final String name)
    {
        return name.startsWith(":") || CHARSET.equals(name);
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
     * never become groups, so they can only be seen through the principal manager, which is the same place the local
     * groups come from -- which is what lets the two be used interchangeably.
     *
     * @param request the request being served
     * @return the requester, holding at least their own name when the principals cannot be read
     */
    @NotNull
    private static Requester requester(@NotNull final SlingJakartaHttpServletRequest request)
    {
        final ResourceResolver resolver = request.getResourceResolver();
        // Anonymous access is refused before a request reaches here, so there is always somebody; the fallback only
        // keeps a resolver that will not say who it is from turning into a failure with no explanation
        final String id = Objects.requireNonNullElse(resolver.getUserID(), "");
        final Set<String> principals = new TreeSet<>();
        principals.add(id);
        final Session session = resolver.adaptTo(Session.class);
        if (session instanceof JackrabbitSession) {
            collectPrincipals((JackrabbitSession) session, id, principals);
        }
        return new Requester(id, principals);
    }

    /**
     * Adds every principal an account holds to the given set, leaving it as it is if the repository will not say.
     *
     * @param session the requester's own session
     * @param id the account the request is made as
     * @param principals the set being filled
     */
    private static void collectPrincipals(@NotNull final JackrabbitSession session, @NotNull final String id,
        @NotNull final Set<String> principals)
    {
        try {
            final Authorizable account = session.getUserManager().getAuthorizable(id);
            if (account == null) {
                return;
            }
            final Principal own = account.getPrincipal();
            principals.add(own.getName());
            final PrincipalManager manager = session.getPrincipalManager();
            final PrincipalIterator groups = manager.getGroupMembership(own);
            while (groups.hasNext()) {
                principals.add(groups.nextPrincipal().getName());
            }
        } catch (final RepositoryException e) {
            // Failing closed: without the group principals the requester is treated as holding nothing but their own
            // name, so they can still read and change their own profile and nothing else
            LOGGER.warn("Cannot read the principals of {}: {}", id, e.getMessage(), e);
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
    private static void write(@NotNull final SlingJakartaHttpServletResponse response, final int status,
        @NotNull final String body) throws IOException
    {
        response.setStatus(status);
        response.setContentType(JSON);
        // What this answers with depends on who asked, while the URL a person's own profile is read from does not
        // mention them at all, so nothing along the way may keep a copy to hand to somebody else
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body);
    }
}
