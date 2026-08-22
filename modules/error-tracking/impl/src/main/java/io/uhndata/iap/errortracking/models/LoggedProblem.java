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
package io.uhndata.iap.errortracking.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

/**
 * A recorded error that nothing was thrown for: something the instance found wrong and could only shrug at, most
 * often a mis-authored definition. Where the code noticed is the {@link #getComponent() component} and the
 * {@link #getOperation() operation}; what it was looking at is in the {@link #getSubjects() subjects}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { LoggedProblem.class, LoggedError.class },
    resourceType = LoggedProblem.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LoggedProblem extends LoggedError
{
    /** The Sling resource type of a recorded error nothing was thrown for. */
    public static final String RESOURCE_TYPE = "err/LoggedProblem";

    /** What is wrong. */
    @ValueMapValue
    private String problem;

    /**
     * What is wrong, a short phrase chosen in code such as {@code unknown comparator}.
     *
     * @return the phrase, never {@code null} in a well-formed record
     */
    @NotNull
    public String getProblem()
    {
        return this.problem == null ? "" : this.problem;
    }

    @Override
    @NotNull
    public String getSummary()
    {
        return this.getProblem();
    }
}
