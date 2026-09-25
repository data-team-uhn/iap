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
package io.uhndata.iap.documents.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * How to reach the document daemon. Read by {@link DaemonConnection}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Document daemon connection",
    description = "How to reach the document daemon that turns uploaded documents into text.")
public @interface DaemonConnectionConfiguration
{
    /**
     * Where the daemon listens.
     *
     * @return the base URL
     */
    @AttributeDefinition(name = "Daemon URL",
        description = "Where the document daemon listens, for example http://localhost:18765.")
    String daemonUrl() default DaemonConnection.DEFAULT_URL;

    /**
     * How long to wait for the daemon to answer.
     *
     * @return a number of seconds
     */
    @AttributeDefinition(name = "Response timeout",
        description = "Seconds to wait for the daemon to answer a request. Zero or less uses the default of 30.")
    long responseTimeout() default DaemonConnection.DEFAULT_RESPONSE_TIMEOUT;

    /**
     * The daemon's own access token.
     *
     * @return the token, empty to use the IAP_DOCLING_TOKEN environment variable
     */
    @AttributeDefinition(name = "Daemon token", type = AttributeType.PASSWORD,
        description = "The bearer token the daemon requires, when it requires one. Leave empty to use the "
            + "IAP_DOCLING_TOKEN environment variable.")
    String daemonToken() default "";
}
