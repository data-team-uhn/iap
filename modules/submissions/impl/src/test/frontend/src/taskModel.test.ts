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

import {
  COMPLETE_EVENT,
  OPEN_STATUS,
  TASK_INSTANCE_TYPE,
  WORKFLOW_INSTANCE_TYPE,
  getOpeningRequirement,
  tasksFrom
} from "@iap/submissions/taskModel";

const INSTANCES = "/Submissions/a/b/demo-1/wf:instances";

// The instances container, holding one running workflow with a token and the tasks it has raised
const CONTAINER = {
  "@path": INSTANCES,
  "sling:resourceType": "wf/WorkflowInstances",
  "timeOffRequest": {
    "@path": INSTANCES + "/timeOffRequest",
    "sling:resourceType": WORKFLOW_INSTANCE_TYPE,
    "status": "active",
    "token": {
      "@path": INSTANCES + "/timeOffRequest/token",
      "sling:resourceType": "wf/WorkflowToken",
      "currentNodeId": "fillIn",
    },
    "fillIn": {
      "@path": INSTANCES + "/timeOffRequest/fillIn",
      "sling:resourceType": TASK_INSTANCE_TYPE,
      "label": "Say when you want to be away",
      "status": OPEN_STATUS,
      "outcomeOptions": [],
      "@events": [ COMPLETE_EVENT ],
    },
    "checkedBudget": {
      "@path": INSTANCES + "/timeOffRequest/checkedBudget",
      "sling:resourceType": TASK_INSTANCE_TYPE,
      "label": "Check the budget",
      "status": "completed",
    },
    "approveRequest": {
      "@path": INSTANCES + "/timeOffRequest/approveRequest",
      "sling:resourceType": TASK_INSTANCE_TYPE,
      "label": "Approve the request",
      "status": OPEN_STATUS,
      "outcomeOptions": [ "approved", "rejected" ],
      "@events": [],
    },
  },
};

// A container holding one instance, which holds the given task
function holding(task: Record<string, unknown>) {
  return {
    i: { "sling:resourceType": WORKFLOW_INSTANCE_TYPE, "t": { "sling:resourceType": TASK_INSTANCE_TYPE, ...task } },
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
      "@events": [ COMPLETE_EVENT ],
    });
    expect(tasks[1].outcomeOptions).toEqual([ "approved", "rejected" ]);
  });

  it("reads a lone value, which arrives as a bare string rather than an array", () => {
    const [ task ] = tasksFrom(holding({
      "@path": INSTANCES + "/i/t", "status": OPEN_STATUS, "outcomeOptions": "acknowledged", "@events": COMPLETE_EVENT,
    }));

    expect(task.outcomeOptions).toEqual([ "acknowledged" ]);
    expect(task["@events"]).toEqual([ COMPLETE_EVENT ]);
  });

  it("falls back to a task's node name when it carries no label", () => {
    expect(tasksFrom(holding({ "@path": INSTANCES + "/i/nameless", "status": OPEN_STATUS }))[0].label)
      .toBe("nameless");
  });

  it("reads nothing from a task serialized without the events the reader may send it", () => {
    expect(tasksFrom(holding({ "@path": INSTANCES + "/i/t", "status": OPEN_STATUS }))[0]["@events"]).toEqual([]);
  });

  it("skips a task the server would not say where to find", () => {
    expect(tasksFrom(holding({ "label": "Nowhere", "status": OPEN_STATUS }))).toEqual([]);
  });

  it("reads nothing from an empty container", () => {
    expect(tasksFrom({})).toEqual([]);
  });

  it("reads the requirement a task asks for, when it names one", () => {
    const [ task ] = tasksFrom(holding({ "@path": INSTANCES + "/i/t", "status": OPEN_STATUS, "requirement": "protocol" }));

    expect(task.requirement).toBe("protocol");
  });
});

describe("getOpeningRequirement", () => {
  // A container holding one instance, which holds the given tasks in order
  function opening(...tasks: Record<string, unknown>[]) {
    const children = Object.fromEntries(tasks.map((task, index) =>
      [ `t${index}`, { "sling:resourceType": TASK_INSTANCE_TYPE, ...task } ]));
    return getOpeningRequirement({ i: { "sling:resourceType": WORKFLOW_INSTANCE_TYPE, ...children } });
  }

  const FIRST = "2026-11-25T23:38:17.876-05:00";
  const LATER = "2026-11-25T23:38:18.109-05:00";

  it("reads the requirement the first task asked for, done or not", () => {
    expect(opening({ status: "completed", requirement: "protocol", "jcr:created": FIRST },
      { status: OPEN_STATUS, requirement: "proposal", "jcr:created": LATER })).toBe("protocol");
  });

  it("takes the task created first, whatever order the server lists the tasks in", () => {
    expect(opening({ status: "completed", requirement: "questionnaire", "jcr:created": LATER },
      { status: "completed", requirement: "protocol", "jcr:created": FIRST })).toBe("protocol");
  });

  it("puts a task with no creation time after the others", () => {
    expect(opening({ status: OPEN_STATUS, requirement: "questionnaire" },
      { status: "completed", requirement: "protocol", "jcr:created": FIRST })).toBe("protocol");
  });

  it("reads nothing when the first task names no requirement, whatever later tasks name", () => {
    expect(opening({ status: OPEN_STATUS, "jcr:created": FIRST },
      { status: OPEN_STATUS, requirement: "proposal", "jcr:created": LATER })).toBeUndefined();
    expect(getOpeningRequirement({})).toBeUndefined();
  });
});
