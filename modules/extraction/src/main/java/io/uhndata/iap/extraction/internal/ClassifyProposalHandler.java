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
package io.uhndata.iap.extraction.internal;

import java.io.IOException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that gives a proposal's category a second look when the gate was not sure of it. Does nothing
 * for a document the gate did not let through, or a category it was sure enough of.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ClassifyProposalHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "classifyProposal";

    @Reference
    private ProposalCategoryService classifier;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        if (!GateProposalHandler.passed(context)
            || !this.classifier.needsSecondLook(GateProposalHandler.recorded(context))) {
            return;
        }
        final File file = GateProposalHandler.gatedFile(context);
        if (file == null) {
            return;
        }
        final CategoryPick pick;
        try {
            pick = this.classifier.classify(file, CategoryCatalog.read(context.getResourceResolver()));
        } catch (final IOException e) {
            throw new PersistenceException("Could not read the parsed document: " + e.getMessage(), e);
        }
        if (pick == null) {
            return;
        }
        final ModifiableValueMap properties = context.getTarget().adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the category on " + context.getTarget().getPath());
        }
        properties.put(ExtractionStatus.CATEGORY, pick.path());
    }
}
