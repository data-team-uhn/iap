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
package io.uhndata.iap.submissions.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping a {@code sub:Screening} node: the AI's feedback on a submission. It is a guide, never a
 * decision.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {Screening.class, Feedback.class},
    resourceType = Screening.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Screening extends Feedback
{
    /** The {@code sling:resourceType} of a {@code sub:Screening} node. */
    public static final String RESOURCE_TYPE = "sub/Screening";

    @ValueMapValue
    private String summary;

    /**
     * A short summary of the whole screening.
     *
     * @return the summary, or {@code null} if the run gave none
     */
    @Nullable
    public String getSummary()
    {
        return this.summary;
    }
}
