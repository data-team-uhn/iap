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
package io.uhndata.iap.schemas.editing.internal.handlers;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.spi.SchemaValidityCheck;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Refuses to go on unless the target version passes every {@link SchemaValidityCheck}: nothing in it would break
 * once it can no longer change. Every problem is reported at once.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CheckPublishableHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "checkPublishable";

    /** Every check a draft must pass: this module's, and whatever others register later. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<SchemaValidityCheck> checks;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Resource target = context.getTarget();
        final SchemaVersion version = SchemaContent.asVersion(target);
        if (version == null) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final List<String> problems = this.checks.stream()
            .flatMap(check -> check.check(target).stream())
            .toList();
        if (!problems.isEmpty()) {
            throw new InvalidStateException("Version " + version.getVersion()
                + " cannot be published yet: " + String.join("; ", problems) + ".");
        }
    }
}
