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
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration for the {@link OidcEndSessionServlet}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP OIDC End Session Servlet",
    description = "Redirects to the OIDC provider's end-session endpoint to complete a logout. The fallback for when "
        + "the logout handler could not end the provider session back-channel.")
public @interface OidcEndSessionConfiguration
{
    /**
     * The provider's end-session (logout) endpoint.
     *
     * @return the absolute URL of the OIDC {@code end_session_endpoint}
     */
    @AttributeDefinition(name = "End-session endpoint",
        description = "The OIDC provider's end_session_endpoint (front-channel, browser-facing), for example "
            + "Keycloak's {realm}/protocol/openid-connect/logout.")
    String endSessionEndpoint() default "";

    /**
     * The OAuth client identifier IAP is registered under.
     *
     * @return the {@code client_id} sent on the end-session request
     */
    @AttributeDefinition(name = "Client ID",
        description = "The client_id IAP is registered as with the provider; the end-session endpoint requires it "
            + "when a post-logout redirect URI is supplied.")
    String clientId() default "";

    /**
     * Where the provider returns the browser after logout.
     *
     * @return the {@code post_logout_redirect_uri}, which must be registered on the provider client
     */
    @AttributeDefinition(name = "Post-logout redirect URI",
        description = "Absolute URL the provider returns the browser to after logout; must be registered as a valid "
            + "post-logout redirect URI on the provider client.")
    String postLogoutRedirectUri() default "";
}
