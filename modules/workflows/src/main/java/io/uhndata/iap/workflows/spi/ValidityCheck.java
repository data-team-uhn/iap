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
package io.uhndata.iap.workflows.spi;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * A rule something must follow before a lifecycle moves it on: a workflow version before it may run, a schema
 * version before it is published, or anything else a module protects. The {@code checkValid} service task runs every
 * registered check that applies to its target and refuses it with all the problems they find at once, so registering
 * a check service is all it takes for a module to protect what it adds.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ValidityCheck
{
    /**
     * Whether this check has anything to say about a resource, such as a version of the kind it knows.
     *
     * @param target the resource a {@code checkValid} step acts on
     * @return {@code true} if {@link #check} should run on it
     */
    boolean appliesTo(@NotNull Resource target);

    /**
     * What stops a resource from moving on, as far as this check is concerned.
     *
     * @param target a resource this check applies to
     * @return one sentence per problem; empty when there's no problem
     */
    @NotNull
    List<String> check(@NotNull Resource target);
}
