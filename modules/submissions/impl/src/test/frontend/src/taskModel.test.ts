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

import { COMPLETE, OPEN, TASK_INSTANCE, WORKFLOW_INSTANCE, tasksFrom } from "@iap/submissions/taskModel";

const INSTANCES = "/Submissions/a/b/demo-1/wf:instances";

// The instances container, holding one running workflow with a token and the tasks it has raised
const CONTAINER = {
  "@path": INSTANCES,
  "sling:resourceType": "wf/WorkflowInstances",
  "timeOffRequest": {
    "@path": INSTANCES + "/timeOffRequest",
    "sling:resourceType": WORKFLOW_INSTANCE,
    "status": "active",
    "token": {
      "@path": INSTANCES + "/timeOffRequest/token",
      "sling:resourceType": "wf/WorkflowToken",
      "currentNodeId": "fillIn",
    },
    "fillIn": {
      "@path": INSTANCES + "/timeOffRequest/fillIn",
      "sling:resourceType": TASK_INSTANCE,
      "label": "Say when you want to be away",
      "status": OPEN,
      "outcomeOptions": [],
      "@events": [ COMPLETE ],
    },
    "checkedBudget": {
      "@path": INSTANCES + "/timeOffRequest/checkedBudget",
      "sling:resourceType": TASK_INSTANCE,
      "label": "Check the budget",
      "status": "completed",
    },
    "approveRequest": {
      "@path": INSTANCES + "/timeOffRequest/approveRequest",
      "sling:resourceType": TASK_INSTANCE,
      "label": "Approve the request",
      "status": OPEN,
      "outcomeOptions": [ "approved", "rejected" ],
      "@events": [],
    },
  },
};

// A container holding one instance, which holds the given task
function holding(task: Record<string, unknown>) {
  return {
    i: { "sling:resourceType": WORKFLOW_INSTANCE, "t": { "sling:resourceType": TASK_INSTANCE, ...task } },
  };
}

describe("tasksFrom", () => {
  it("reads the tasks a submission's workflows are still waiting on", () => {
    const tasks = tasksFrom(CONTAINER);

    // The completed task is not waiting on anybody, and the token is not a task at all
    expect(tasks.map(task => task.label)).toEqual([ "Say when you want to be away", "Approve the request" ]);
    expect(tasks[0]).toEqual({
      path: INSTANCES + "/timeOffRequest/fillIn",
      label: "Say when you want to be away",
      outcomeOptions: [],
      "@events": [ COMPLETE ],
    });
    expect(tasks[1].outcomeOptions).toEqual([ "approved", "rejected" ]);
  });

  it("reads a lone value, which arrives as a bare string rather than an array", () => {
    const [ task ] = tasksFrom(holding({
      "@path": INSTANCES + "/i/t", "status": OPEN, "outcomeOptions": "acknowledged", "@events": COMPLETE,
    }));

    expect(task.outcomeOptions).toEqual([ "acknowledged" ]);
    expect(task["@events"]).toEqual([ COMPLETE ]);
  });

  it("falls back to a task's node name when it carries no label", () => {
    expect(tasksFrom(holding({ "@path": INSTANCES + "/i/nameless", "status": OPEN }))[0].label)
      .toBe("nameless");
  });

  it("reads nothing from a task serialized without the events the reader may send it", () => {
    // Fail closed: a task the server said nothing about offers nothing
    expect(tasksFrom(holding({ "@path": INSTANCES + "/i/t", "status": OPEN }))[0]["@events"]).toEqual([]);
  });

  it("skips a task the server would not say where to find", () => {
    expect(tasksFrom(holding({ "label": "Nowhere", "status": OPEN }))).toEqual([]);
  });

  it("reads nothing from an empty container", () => {
    expect(tasksFrom({})).toEqual([]);
  });
});
