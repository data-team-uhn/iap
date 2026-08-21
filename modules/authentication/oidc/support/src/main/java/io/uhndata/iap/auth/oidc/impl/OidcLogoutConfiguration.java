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
 * Configuration for the {@link OidcLogoutAuthenticationHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP OIDC Logout Handler",
    description = "Clears the OIDC session cookie on logout, which the Sling OAuth client itself does not.")
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
}
