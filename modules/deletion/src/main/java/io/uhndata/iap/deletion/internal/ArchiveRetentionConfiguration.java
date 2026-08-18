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
package io.uhndata.iap.deletion.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * How long a deleted resource is held in the archive before anybody may destroy it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Archive retention",
    description = "How long a deleted resource must remain in the archive before its entry may be purged.")
public @interface ArchiveRetentionConfiguration
{
    /**
     * The minimum age, in days, an archive entry must reach before it may be purged.
     *
     * @return a number of days, zero or negative for no retention period at all
     */
    @AttributeDefinition(name = "Minimum retention period",
        description = "The number of days an archive entry must have spent in the archive before it may be purged. "
            + "Zero, the default, imposes no floor at all: an entry may be purged the moment it is created. This is "
            + "a floor under purging, never a trigger for it — nothing purges anything automatically — so raising it "
            + "only ever prevents destruction. Counted in calendar days from the deletion.")
    int minimumRetentionDays() default 0;
}
