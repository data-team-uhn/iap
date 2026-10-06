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
package io.uhndata.iap.auth.token.jwt.impl;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration for {@link IapJwtTokenManagerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "JWT token manager",
    description = "How this instance identifies itself in the tokens it exchanges with other IAP instances.")
public @interface IapJwtTokenManagerConfiguration
{
    /**
     * This instance's identity, used both as the issuer of the tokens it mints and as the audience it expects.
     *
     * @return a URI, or a plain string without colons
     */
    @AttributeDefinition(name = "Identity",
        description = "How this instance names itself to its peers, normally its public base URL, such as "
            + "https://iap.example.org; the platform sets it from the IAP_PUBLIC_URL environment variable. It is "
            + "the issuer of every token this instance mints, and the audience a "
            + "token must name for this instance to accept it. A peer must register exactly this value, character "
            + "for character: it is compared without normalization, so a trailing slash makes a different "
            + "identity. A value containing a colon must be an absolute URI.")
    String identity() default "http://localhost:8080";
}
