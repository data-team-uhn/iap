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
package io.uhndata.iap.tags.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * When to sweep up the content whose tags could not be computed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Tag Repair",
    description = "Recomputes tags that a failed computation left out of date.")
public @interface TagRepairConfiguration
{
    /**
     * When the sweep runs.
     *
     * @return a Quartz-readable schedule expression
     */
    @AttributeDefinition(name = "Schedule",
        description = "A Quartz-readable schedule expression determining when the sweep runs, for example "
            + "'0 0 * * * ? *' for hourly. The sweep is driven by an index and writes nothing when there is nothing "
            + "wrong, so running it often costs little. Repairing the content affected by an edited tag definition "
            + "is a separate, deliberate operation, and is never done by this job.")
    String schedule() default "0 0 * * * ? *";

    /**
     * Whether to sweep at all.
     *
     * @return {@code true} if the sweep should be scheduled
     */
    @AttributeDefinition(name = "Enabled",
        description = "Whether to sweep automatically. Turning this off leaves failed computations in place until "
            + "something else writes to the content, or a repair is triggered by hand.")
    boolean enabled() default true;
}
