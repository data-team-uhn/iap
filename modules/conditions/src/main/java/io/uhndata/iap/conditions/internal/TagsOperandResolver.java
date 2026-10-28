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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.spi.TagProcessor;

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
     * The properties holding tag names on a tagged node: the explicitly placed tags, plus the one each propagation
     * phase materializes at commit time, in the order the phases run. Taken from the phases themselves, so that one
     * added later is read too.
     */
    private static final List<String> TAG_PROPERTIES = Stream.concat(Stream.of(TagManager.TAGS_PROPERTY),
        Arrays.stream(TagProcessor.Phase.values()).map(TagProcessor.Phase::getPropertyName)).toList();

    @Override
    public String getSource()
    {
        return "tags";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final Content entity = Optional.ofNullable(OperandResolver.findEnclosingEntity(context)).orElse(context);
        return Operand.of(TAG_PROPERTIES.stream()
            .map(property -> entity.get(property, String[].class))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .distinct()
            .toArray(String[]::new), OperandType.TEXT);
    }
}
