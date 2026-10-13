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
package io.uhndata.iap.workflows.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration for {@link Delegation}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Workflow delegation",
    description = "Who may send workflow events on behalf of someone else.")
public @interface DelegationConfiguration
{
    /**
     * The group whose members may send events on behalf of another user.
     *
     * @return a group's principal name
     */
    @AttributeDefinition(name = "Delegates",
        description = "The principal name of the group whose members may send an event on behalf of another user, "
            + "with the :onBehalfOf request parameter. The admin user always may.")
    String delegates() default "iap-administrators";
}
