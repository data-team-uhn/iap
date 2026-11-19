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
package io.uhndata.iap.submissions.internal;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.AnswerDependencies;
import io.uhndata.iap.conditions.api.OperandType;
import io.uhndata.iap.conditions.spi.Candidate;
import io.uhndata.iap.conditions.spi.CandidateProvider;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;

/**
 * Lists the questions whose answers a condition on a part of a schema version can compare. These are every question
 * of the version except the part itself and the questions inside it. Asked about the version itself, it lists every
 * question, which the schema editor needs to show the conditions of every part.
 *
 * <p>
 * Each candidate says what the question's answers hold: their type, whether there can be several, and the options to
 * pick from. The options are listed on the question, or read from the items its {@code optionsFrom} names. A
 * candidate's value is the question's {@code jcr:uuid}, and its alias the question's path in the version. Its detail
 * is the question's node name, which tells apart two questions with the same text.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CandidateProvider.class)
public class AnswerCandidates implements CandidateProvider
{
    /** The comparison type of each question data type; a file question has none. */
    private static final Map<String, OperandType> COMPARISON_TYPES = Map.of("text", OperandType.TEXT,
        "long", OperandType.LONG, "double", OperandType.DOUBLE, "boolean", OperandType.BOOLEAN,
        "date", OperandType.DATE);

    @Override
    public String getSource()
    {
        return AnswerDependencies.ANSWER_SOURCE;
    }

    @Override
    public List<Candidate> getCandidates(final Resource context)
    {
        Resource version = context;
        while (version != null && !version.isResourceType(SchemaVersion.RESOURCE_TYPE)) {
            version = version.getParent();
        }
        if (version == null) {
            return List.of();
        }
        final String part = context.getPath();
        final String versionPath = version.getPath();
        final boolean whole = part.equals(versionPath);
        return descendants(version)
            .filter(node -> node.isResourceType(Question.RESOURCE_TYPE))
            .filter(question -> whole
                || !question.getPath().equals(part) && !question.getPath().startsWith(part + "/"))
            .filter(question -> question.getValueMap().containsKey("jcr:uuid"))
            .map(question -> candidate(question, versionPath))
            .toList();
    }

    private static Candidate candidate(final Resource question, final String versionPath)
    {
        final ValueMap properties = question.getValueMap();
        // One answer unless it says otherwise; 0 means any number
        final boolean multiple = !Long.valueOf(1L).equals(properties.get("maxAnswers", 1L));
        // A stored answer operand names its question by jcr:uuid, or by its path in the version
        return new Candidate(properties.get("jcr:uuid", ""), headingOf(question),
            COMPARISON_TYPES.get(properties.get("dataType", "text")), multiple, choicesOf(question),
            List.of(question.getPath().substring(versionPath.length() + 1)), question.getName());
    }

    private static List<Candidate.Choice> choicesOf(final Resource question)
    {
        final List<Candidate.Choice> listed = children(question)
            .filter(child -> child.isResourceType(AnswerOption.RESOURCE_TYPE))
            .map(option -> {
                final String value = option.getValueMap().get("value", "");
                return new Candidate.Choice(value, option.getValueMap().get("label", value));
            })
            .toList();
        final String optionsFrom = question.getValueMap().get("optionsFrom", String.class);
        if (!listed.isEmpty() || optionsFrom == null) {
            return listed;
        }
        final Resource items = question.getResourceResolver().getResource(optionsFrom);
        return items == null ? List.of() : descendants(items)
            .filter(item -> !item.getName().contains(":"))
            .map(item -> new Candidate.Choice(item.getPath(), item.getValueMap().get("title",
                item.getValueMap().get("label", item.getName()))))
            .toList();
    }

    /** What a part is called where it is shown: a question's text, or else its label, title or name. */
    private static String headingOf(final Resource part)
    {
        final ValueMap properties = part.getValueMap();
        return properties.get("text", properties.get("label", properties.get("title", part.getName())));
    }

    private static Stream<Resource> children(final Resource node)
    {
        return StreamSupport.stream(node.getChildren().spliterator(), false);
    }

    private static Stream<Resource> descendants(final Resource node)
    {
        return children(node).flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
