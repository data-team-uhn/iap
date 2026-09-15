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
package io.uhndata.iap.auth.oidc.impl;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration for the {@link OidcLogoutAuthenticationHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP OIDC Logout Handler",
    description = "Clears the OIDC session cookie on logout, which the Sling OAuth client itself does not, and ends "
        + "the provider's SSO session back-channel so the next sign-in asks for credentials again.")
public @interface OidcLogoutConfiguration
{
    /**
     * The cookie to expire on logout.
     *
     * @return the cookie name, which must match the OAuth client's SlingLoginCookieManager
     */
    @AttributeDefinition(name = "Cookie name",
        description = "The session cookie to expire on logout. Must match the cookie name configured on the OAuth "
            + "client's SlingLoginCookieManager; the two default to the same value.")
    String cookieName() default "sling.oidcauth";

    /**
     * The local path to steer Sling's post-logout redirect to for an OIDC session, so the provider-side
     * logout can be completed. Blank disables the redirect (the cookie is still expired).
     *
     * @return the local path served by {@link OidcEndSessionServlet}
     */
    @AttributeDefinition(name = "Post-logout path",
        description = "Local path Sling's post-logout redirect is steered to for an OIDC session, so the external "
            + "logout can be completed. Must match the path served by OidcEndSessionServlet; blank disables it.")
    String postLogoutPath() default "/system/sling/oauth/logout";

    /**
     * Where the user's refresh token is stored, relative to their home node.
     *
     * @return a relative property path, which must match the sync handler's user.propertyMapping
     */
    @AttributeDefinition(name = "Refresh token property",
        description = "Path of the stored refresh token, relative to the user's home node. Must match the entry in "
            + "the DefaultSyncHandler's user.propertyMapping that persists it, and the OAuth client's "
            + "storeRefreshToken must be on, or there is nothing to read and the back-channel logout is skipped.")
    String refreshTokenPath() default "oauth/refresh_token";

    /**
     * The provider's logout endpoint, as IAP reaches it in-network. Blank disables the back-channel call.
     *
     * @return the absolute back-channel URL of the OIDC {@code end_session_endpoint}
     */
    @AttributeDefinition(name = "Back-channel logout endpoint",
        description = "The provider's logout endpoint as IAP reaches it in-network, e.g. Keycloak's "
            + "{realm}/protocol/openid-connect/logout. IAP calls this itself, so it must be the back-channel URL, "
            + "not the browser-facing one the end-session servlet redirects to. Blank disables the call.")
    String backchannelLogoutEndpoint() default "";

    /**
     * The OAuth client identifier IAP is registered under.
     *
     * @return the {@code client_id} sent on the back-channel logout request
     */
    @AttributeDefinition(name = "Client ID",
        description = "The client_id IAP is registered as with the provider.")
    String clientId() default "";

    /**
     * The confidential client's secret, which authenticates the back-channel call.
     *
     * @return the {@code client_secret} sent on the back-channel logout request
     */
    @AttributeDefinition(name = "Client secret",
        description = "The confidential client's secret. The logout endpoint refuses an unauthenticated caller, so "
            + "ending a session requires it.",
        type = AttributeType.PASSWORD)
    String clientSecret() default "";
}
