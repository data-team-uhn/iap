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
package io.uhndata.iap.statistics.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * How often the metrics are worked out again.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP Statistics Refresh",
    description = "When the reported metrics are worked out again from the recorded history.")
public @interface StatisticsRefreshConfiguration
{
    /**
     * When the refresh runs.
     *
     * @return a Quartz-readable schedule expression
     */
    @AttributeDefinition(name = "Schedule",
        description = "A Quartz-readable schedule expression determining when the metrics are worked out "
            + "again, for example '0 30 2 * * ? *' for nightly at 2:30, or '0 0 * * * ? *' for hourly. "
            + "These are monthly cohorts, so nightly is enough for what they are asked to show; what the "
            + "figures cost is a full read of the recorded history, which grows with the deployment. The "
            + "date of the last refresh is shown to readers, so a slower schedule is visible rather than "
            + "silent.")
    String schedule() default StatisticsRefresh.DEFAULT_SCHEDULE;
}
