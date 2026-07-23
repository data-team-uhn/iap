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
package io.uhndata.iap.conditions.internal;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;

/**
 * Resolves {@code tags} operands: the effective tags of the enclosing entity, e.g. everything a submission is
 * currently tagged with. Typically combined with the {@code includes}/{@code excludes} family of comparators
 * against literal tag names. The operand's stored value is not used.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class TagsOperandResolver implements OperandResolver
{
    /**
     * The properties holding tag names on a tagged node: the explicitly placed tags, plus the tags aggregated from
     * descendants and inherited from ancestors, both materialized at commit time by the tagging subsystem.
     */
    private static final String[] TAG_PROPERTIES = { "tags", "aggregatedTags", "inheritedTags" };

    @Override
    public String getSource()
    {
        return "tags";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Resource context)
    {
        final ValueMap properties = Optional.ofNullable(OperandResolver.findEnclosingEntity(context))
            .orElse(context).getValueMap();
        return Operand.of(Arrays.stream(TAG_PROPERTIES)
            .map(property -> properties.get(property, String[].class))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .distinct()
            .toArray(String[]::new), OperandType.TEXT);
    }
}
