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
 * Which identity provider the session cookies of {@link SessionLoginCookieManager} speak for.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP identity provider session cookie",
    description = "Keeps the identity provider's sign-in in IAP's session cookie, which the session limits end.")
public @interface SessionLoginCookieManagerConfiguration
{
    /**
     * The identity provider the signed-in users come from.
     *
     * @return its name
     */
    @AttributeDefinition(name = "Identity provider",
        description = "The name of the identity provider the authentication handler registers, its idp setting.")
    String idpName() default "keycloak";
}
