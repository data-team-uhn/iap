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

import io.uhndata.iap.schemas.models.Fulfiller;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * Something filed against a requirement by a module this one has never heard of, standing in for the real ones.
 *
 * <p>The pair to {@link ForeignRequirement}: together they are a whole kind of question and answer declared
 * elsewhere, which is what the completeness walk has to handle without recognising either.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { ForeignFulfiller.class, Fulfiller.class },
    resourceType = ForeignFulfiller.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ForeignFulfiller extends Fulfiller
{
    /** Deliberately in nobody's namespace: this module must not recognise it. */
    public static final String RESOURCE_TYPE = "elsewhere/ForeignFulfiller";

    @ValueMapValue
    private String fulfills;

    /** Whether this one counts, so a test can file something that does not meet what it names. */
    @ValueMapValue
    private boolean meets;

    @Override
    @Nullable
    public Requirement getFulfills()
    {
        return this.getReference(this.fulfills, Requirement.class);
    }

    @Override
    public boolean isFulfilling()
    {
        return this.meets;
    }
}
