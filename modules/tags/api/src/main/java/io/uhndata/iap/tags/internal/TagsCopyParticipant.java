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
package io.uhndata.iap.tags.internal;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagProcessor;
import io.uhndata.iap.utils.copy.CopyParticipant;

/**
 * Keeps computed tags out of copies: they are derived from where a node sits and what it holds, and the copy gets
 * its own when it is saved. The tags placed by hand are copied like any other property.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CopyParticipant.class)
public class TagsCopyParticipant implements CopyParticipant
{
    private static final Set<String> COMPUTED = Stream.concat(Stream.of(TagManager.COMPUTATION_STATE_PROPERTY),
        Arrays.stream(TagProcessor.Phase.values()).map(TagProcessor.Phase::getPropertyName))
        .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean skips(final Property property) throws RepositoryException
    {
        return COMPUTED.contains(property.getName());
    }
}
