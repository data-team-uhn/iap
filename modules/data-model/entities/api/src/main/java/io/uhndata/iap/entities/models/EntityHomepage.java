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
package io.uhndata.iap.entities.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping an {@code iap:EntityHomepage} node, the root container of a collection of entities of one
 * type (e.g. {@code /Schemas}, {@code /Submissions}). It exposes no properties beyond the generic
 * {@code iap:Content} ones; concrete homepage subtypes add typed listing methods for their entities.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = "iap/EntityHomepage",
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class EntityHomepage extends Content
{
}
