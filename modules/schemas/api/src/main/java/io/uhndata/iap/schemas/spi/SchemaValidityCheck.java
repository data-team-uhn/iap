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
package io.uhndata.iap.schemas.spi;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * A rule a schema version must follow to be valid: something a submitter or a condition will lean on once the
 * version can no longer change. Publishing a draft runs every registered check and refuses it with all the
 * problems they find at once, so registering a new check service is all it takes for a module to protect the
 * parts of a schema it adds.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SchemaValidityCheck
{
    /**
     * What makes a version invalid, as far as this check is concerned.
     *
     * @param version the schema version to check, such as a draft about to be published
     * @return one sentence per problem, naming the part of the version it was found in; empty when there's no problem
     */
    @NotNull
    List<String> check(@NotNull Resource version);
}
