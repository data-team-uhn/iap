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

import type { ReactElement } from "react";

import { CircularProgress, Stack, Typography } from "@mui/material";

import { messageOf } from "@iap/frontend-commons/requestFailure";

import { isAnswered } from "./intake";
import { getOpenStep, getStage, getStageRequirements, isNewAnswer } from "./routeModel";
import RouteStep from "./RouteStep";

import type { FormQuestion, FormRequirement, Requirement, SubmissionForm } from "./submissionForm";
import type { SubmissionTask } from "./taskModel";

const ANSWER_FIRST = "Answer the required questions to go on.";

function ignore(): void {
  // A failed save is reported on its field, and leaves the way as it was
}

export interface RouteViewProps {
  form: SubmissionForm;
  // The form whose answer picks the way the request goes
  choice: FormRequirement;
  // The tasks the workflow waits for
  tasks: SubmissionTask[];
  // The step last completed here; the view waits until the workflow has moved past it
  completedStep?: string;
  renderPanel: (requirement: Requirement, locked?: boolean,
    onAnswer?: (question: FormQuestion, values: string[]) => void) => ReactElement;
  // The reading steps over these requirements; `sendUploads` is the upload step's Next
  renderReadingSteps: (requirements: Requirement[], sendUploads?: () => Promise<void>) => ReactElement;
  answer: (question: FormQuestion, values: string[]) => Promise<unknown>;
  completeStep: (task: SubmissionTask) => Promise<void>;
  // Called once a form step is completed, since the platform then writes the document from the answers
  onFormCompleted: () => void;
  onSwitchFailed: (message: string) => void;
}

// The choice, then the step the workflow waits for under it
function RouteView({
  form, choice, tasks, completedStep, renderPanel, renderReadingSteps, answer, completeStep, onFormCompleted,
  onSwitchFailed,
}: RouteViewProps) {
  const open = getOpenStep(tasks);
  if (open !== undefined && open.path === completedStep) {
    return (
      <>
        {renderPanel(choice, true)}
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <CircularProgress size={16} />
          <Typography variant="placeholder">Going on to the next step…</Typography>
        </Stack>
      </>
    );
  }
  const stage = getStage(form, choice, open);
  // The answer can change only while a step of the submitter's is open, since completing it is what makes
  // the workflow read the answer again
  const answerChoice = (question: FormQuestion, values: string[]) => {
    const save = answer(question, values);
    if (stage.name !== "choose" && open !== undefined && isNewAnswer(question, values)) {
      save.then(() => completeStep(open), ignore).catch((e: unknown) => onSwitchFailed(messageOf(e)));
    }
  };
  const choicePanel = renderPanel(choice, open === undefined, answerChoice);
  const shown = getStageRequirements(form, choice, stage);
  switch (stage.name) {
    case "choose": {
      const next = stage.step;
      return (
        <RouteStep whyNotNext={isAnswered(choice) ? undefined : ANSWER_FIRST} onNext={() => completeStep(next)}>
          {choicePanel}
        </RouteStep>
      );
    }
    case "read": {
      const uploadStep = stage.step?.requirement === undefined ? undefined : stage.step;
      return (
        <>
          {choicePanel}
          {renderReadingSteps(shown, uploadStep && (() => completeStep(uploadStep)))}
        </>
      );
    }
    case "fill": {
      const next = stage.step;
      return (
        <>
          {choicePanel}
          <RouteStep
            whyNotNext={isAnswered(stage.asked) ? undefined : ANSWER_FIRST}
            onNext={() => completeStep(next).then(onFormCompleted)}
          >
            {shown.map(requirement => renderPanel(requirement))}
          </RouteStep>
        </>
      );
    }
    case "send":
      return (
        <>
          {choicePanel}
          {shown.map(requirement => renderPanel(requirement))}
        </>
      );
  }
}

export default RouteView;
