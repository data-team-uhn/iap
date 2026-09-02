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
package io.uhndata.iap.schemas.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

/**
 * The plainest possible thing filed against a requirement: it says what it answers and nothing more.
 *
 * <p>This module declares no concrete kind of its own — every one of them lives where the thing being answered
 * is stored — so the base class is exercised through a stand-in rather than through a real one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { TestFulfiller.class, Fulfiller.class },
    resourceType = TestFulfiller.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TestFulfiller extends Fulfiller
{
    /** Deliberately in nobody's namespace. */
    public static final String RESOURCE_TYPE = "test/Fulfiller";

    @ValueMapValue
    private String fulfills;

    @Override
    @Nullable
    public Requirement getFulfills()
    {
        return this.getReference(this.fulfills, Requirement.class);
    }
}
