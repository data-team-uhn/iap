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

import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * PRETOTYPE. The study team's response to one of the AI pre-review's findings, before they send the submission: why
 * they leave it as it is. The payload's {@code comment} names the finding, by path, and {@code text} says why; it is
 * kept as a reply to the finding, one per finding, which a later response replaces and an empty one removes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class AiResponseHandler implements ServiceTaskHandler
{
    private static final String RESPONSE_NODE = "response";

    private static final String TEXT_PROPERTY = "text";

    @Override
    public String getName()
    {
        return "aiResponse";
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource submission = ExecutionHost.of(context);
        final Resource finding = finding(submission, Payloads.text(context.getEvent(), "comment"));
        final String text = Payloads.text(context.getEvent(), TEXT_PROPERTY);
        final Resource existing = finding.getChild(RESPONSE_NODE);
        if (text == null) {
            if (existing != null) {
                submission.getResourceResolver().delete(existing);
            }
        } else if (existing != null) {
            Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class), "The engine can write here")
                .put(TEXT_PROPERTY, text);
        } else {
            submission.getResourceResolver().create(finding, RESPONSE_NODE, Map.of("jcr:primaryType", "sub:Reply",
                TEXT_PROPERTY, text, "author", context.getActor()));
        }
    }

    // A comment of the submission's AI pre-review, by path
    private static Resource finding(final Resource submission, final String path) throws InvalidPayloadException
    {
        final Resource screening = submission.getChild("screening");
        final Resource found = path == null ? null : submission.getResourceResolver().getResource(path);
        if (screening == null || found == null || !path.startsWith(screening.getPath() + "/")
            || !"sub/Comment".equals(found.getResourceType())) {
            throw new InvalidPayloadException("There is no AI pre-review finding " + path + " on this submission");
        }
        return found;
    }
}
