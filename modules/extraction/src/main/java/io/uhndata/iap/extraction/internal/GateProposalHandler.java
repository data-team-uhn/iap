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
import java.util.List;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.extraction.internal.ProposalGateService.GateDecision;
import io.uhndata.iap.extraction.internal.ProposalGateService.Verdict;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that puts a submission's document through the gate: is it a proposal, and of what kind. What
 * the gate decided goes on the submission and into the execution's variables, which is how the steps after this
 * one know whether there is anything left for them to do.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class GateProposalHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "gateProposal";

    /** The variable holding the verdict, as {@link Verdict#name()}. */
    static final String VERDICT_VARIABLE = "verdict";

    /** What the gate asks, as the call record names it. */
    static final String GATE_QUESTION = "is_protocol";

    /** The variable holding the path of the file the gate read, for the steps that read it again. */
    static final String FILE_VARIABLE = "file";

    /** The variable holding how sure the gate was of its category, or nothing when it picked none. */
    static final String CATEGORY_CONFIDENCE_VARIABLE = "categoryConfidence";

    @Reference
    private ProposalGateService gate;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final File file = SubmissionFiles.firstParsed(SubmissionFiles.submission(target));
        if (file == null) {
            ExtractionStatus.record(target, ExtractionStatus.FAILED, "No uploaded document could be read");
            return;
        }
        final Resource fileResource = context.getResourceResolver().getResource(file.getPath());
        final GateDecision decision;
        final long started = System.nanoTime();
        try {
            decision = this.gate.evaluate(file, CategoryCatalog.read(context.getResourceResolver()));
        } catch (final IOException e) {
            throw new PersistenceException("Could not read the parsed document: " + e.getMessage(), e);
        }
        if (fileResource != null) {
            this.gate.applyTags(fileResource, decision);
            // The gate answers from an opening and a table of contents, never from a chunk, so it records
            // that it read none. Every later pass measures its coverage against these lines.
            LlmCallTracker.append(fileResource, LlmCallTracker.GATE, List.of(GATE_QUESTION), List.of(),
                LlmCallTracker.elapsedMs(started),
                decision.verdict() == ProposalGateService.Verdict.UNDETERMINED
                    ? LlmCallTracker.DEGRADED : LlmCallTracker.OK);
        }
        context.setVariable(VERDICT_VARIABLE, decision.verdict().name());
        context.setVariable(FILE_VARIABLE, file.getPath());
        context.setVariable(CATEGORY_CONFIDENCE_VARIABLE,
            decision.category() == null ? null : decision.category().confidence());
        record(target, decision);
    }

    private static void record(final Resource target, final GateDecision decision) throws PersistenceException
    {
        final ModifiableValueMap properties = target.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the gate's decision on " + target.getPath());
        }
        properties.put(ExtractionStatus.VERDICT, decision.verdict().name());
        if (decision.category() != null) {
            properties.put(ExtractionStatus.CATEGORY, decision.category().path());
        }
        switch (decision.verdict()) {
            case PROPOSAL -> ExtractionStatus.record(target, ExtractionStatus.RUNNING, null);
            case NOT_PROPOSAL -> ExtractionStatus.record(target, ExtractionStatus.NOT_PROPOSAL, decision.reasoning());
            default -> ExtractionStatus.record(target, ExtractionStatus.UNDETERMINED,
                ExtractionStatus.UNDETERMINED_MESSAGE);
        }
    }

    /**
     * Whether the gate let the document through, as the steps after it read it back from the variables.
     *
     * @param context the execution
     * @return {@code true} when the verdict was {@link Verdict#PROPOSAL}
     */
    static boolean passed(final WorkflowTaskContext context)
    {
        return Verdict.PROPOSAL.name().equals(context.getVariable(VERDICT_VARIABLE));
    }

    /**
     * The file the gate read, as the steps after it read it back from the variables.
     *
     * @param context the execution
     * @return the file, or {@code null} when it cannot be read back
     */
    static File gatedFile(final WorkflowTaskContext context)
    {
        final Object path = context.getVariable(FILE_VARIABLE);
        final Resource resource = path instanceof String ? context.getResourceResolver().getResource((String) path)
            : null;
        return resource == null ? null : resource.adaptTo(File.class);
    }

    /**
     * The gate's category pick, as recorded on the submission and in the variables.
     *
     * @param context the execution
     * @return the pick, or {@code null} when the gate made none
     */
    static CategoryPick pick(final WorkflowTaskContext context)
    {
        final String category = context.getTarget().getValueMap().get(ExtractionStatus.CATEGORY, String.class);
        final Object confidence = context.getVariable(CATEGORY_CONFIDENCE_VARIABLE);
        return category == null || !(confidence instanceof Double) ? null
            : new CategoryPick(category, (Double) confidence);
    }

    /**
     * A decision carrying only the category, for the second look to weigh up.
     *
     * @param context the execution
     * @return a decision with the gate's pick
     */
    static GateDecision recorded(final WorkflowTaskContext context)
    {
        return new GateDecision(Verdict.PROPOSAL, 0.0, "", List.of(), pick(context));
    }
}
