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
 * A recorded error that something was thrown for. Besides what every recorded error has, it keeps the class of the
 * throwable and the stack trace of one occurrence.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { LoggedFailure.class, LoggedError.class },
    resourceType = LoggedFailure.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LoggedFailure extends LoggedError
{
    /** The Sling resource type of a recorded error something was thrown for. */
    public static final String RESOURCE_TYPE = "err/LoggedFailure";

    /** The class name of the outermost throwable. */
    @ValueMapValue
    private String type;

    /** The stack trace of the first recorded occurrence. */
    @ValueMapValue
    private String stackTrace;

    /**
     * The class name of what was thrown, such as {@code java.lang.IllegalStateException}. {@link #getType()} is
     * something else: this node's Sling resource type.
     *
     * @return a fully qualified class name; empty only in a malformed record
     */
    @NotNull
    public String getThrowableType()
    {
        return this.type == null ? "" : this.type;
    }

    @Override
    @NotNull
    public String getSummary()
    {
        return this.getThrowableType();
    }

    /**
     * The stack trace of the first recorded occurrence, causes included, as a log file prints it. Later occurrences
     * may carry other messages.
     *
     * @return the printed trace; empty only in a malformed record
     */
    @NotNull
    public String getStackTrace()
    {
        return this.stackTrace == null ? "" : this.stackTrace;
    }
}
