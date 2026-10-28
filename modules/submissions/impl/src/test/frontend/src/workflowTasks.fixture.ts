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

import { COMPLETE_EVENT, OPEN_STATUS, TASK_INSTANCE_TYPE, WORKFLOW_INSTANCE_TYPE } from "@iap/submissions/taskModel";

// The label of the step that sends a request on
export const SEND_LABEL = "Say when you want to be away";

// A step with nothing to decide, which the reader may complete: a request waiting to be sent
export const SEND = { "label": SEND_LABEL, "status": OPEN_STATUS, "@events": [ COMPLETE_EVENT ] };

// A submission's workflow container, holding one running workflow parked on the given tasks, by name
export function waitingOn(submission: string, tasks: Record<string, Record<string, unknown>>) {
  const instance = `${submission}/wf:instances/timeOffRequest`;
  const children: Record<string, Record<string, unknown>> = Object.fromEntries(Object.entries(tasks)
    .map(([ name, task ]) => [ name,
      { "@path": `${instance}/${name}`, "sling:resourceType": TASK_INSTANCE_TYPE, ...task } ]));
  return { timeOffRequest: { "@path": instance, "sling:resourceType": WORKFLOW_INSTANCE_TYPE, ...children } };
}
