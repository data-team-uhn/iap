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
package io.uhndata.iap.demo.review;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * PRETOTYPE. Readies a submission for the review queue when it is sent: gives it an {@code identifier} such as
 * {@code IA-00042}, the next free one, and copies onto it the answers to the questions marked for listings, under
 * the name each question's {@code listing} gives, so a grid can sort and filter on them. The answers to the
 * questions marked for {@code routing} become variables of the run under that name, for its gateways to route on:
 * a gateway cannot name a question by its path.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class PrepareForReviewHandler implements ServiceTaskHandler
{
    /** The property holding the submission's identifier. */
    public static final String IDENTIFIER_PROPERTY = "identifier";

    private static final Pattern IDENTIFIER_NUMBER = Pattern.compile("IA-(\\d+)");

    @Override
    public String getName()
    {
        return "prepareForReview";
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource host = ExecutionHost.of(context);
        final ModifiableValueMap properties = Objects.requireNonNull(host.adaptTo(ModifiableValueMap.class),
            "A submission's properties can be written by the engine");
        if (!properties.containsKey(IDENTIFIER_PROPERTY)) {
            properties.put(IDENTIFIER_PROPERTY, String.format("IA-%05d", highestNumber(host) + 1));
        }
        final Submission submission = Objects.requireNonNull(host.adaptTo(Submission.class),
            "The host is a submission");
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            final String[] values = answer.getValue();
            if (question == null || values == null || values.length == 0) {
                continue;
            }
            final String listing = question.get("listing", String.class);
            if (listing != null) {
                properties.put(listing, values[0]);
            }
            final String routing = question.get("routing", String.class);
            if (routing != null) {
                setVariable(host, routing, values[0]);
            }
        }
    }

    // Where the engine keeps the outcome its gateways route on: a variable node on each running instance
    private static void setVariable(final Resource host, final String name, final String value)
        throws PersistenceException
    {
        final Resource instances = host.getChild("wf:instances");
        if (instances == null) {
            return;
        }
        for (final Resource instance : instances.getChildren()) {
            if (!"active".equals(instance.getValueMap().get("status", String.class))) {
                continue;
            }
            final Resource existing = instance.getChild(name);
            if (existing == null) {
                host.getResourceResolver().create(instance, name,
                    Map.of("jcr:primaryType", "wf:Variable", "dataType", "string", "stringValue", value));
            } else {
                Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class), "Writable").put("stringValue",
                    value);
            }
        }
    }

    private static int highestNumber(final Resource host)
    {
        int highest = 0;
        final Iterator<Resource> numbered = host.getResourceResolver().findResources(
            "SELECT * FROM [sub:Submission] WHERE [" + IDENTIFIER_PROPERTY + "] IS NOT NULL", "JCR-SQL2");
        while (numbered.hasNext()) {
            final String identifier = numbered.next().getValueMap().get(IDENTIFIER_PROPERTY, "");
            final Matcher matcher = IDENTIFIER_NUMBER.matcher(identifier);
            if (matcher.matches()) {
                highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
            }
        }
        return highest;
    }
}
