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

import { afterEach, describe, expect, it, vi } from "vitest";

import { completeTask, fetchOpenTasks } from "@iap/submissions/submissionTasks";

const PATH = "/Submissions/a/b/demo-1";

const INSTANCES = PATH + "/wf:instances";

// The instances container as the deep serialization returns it: one running workflow, holding the
// things it has raised — a token, a variable, and the tasks
const CONTAINER = {
  "@path": INSTANCES,
  "sling:resourceType": "wf/WorkflowInstances",
  "timeOffRequest": {
    "@path": INSTANCES + "/timeOffRequest",
    "sling:resourceType": "wf/WorkflowInstance",
    "status": "active",
    "token": {
      "@path": INSTANCES + "/timeOffRequest/token",
      "sling:resourceType": "wf/WorkflowToken",
      "currentNodeId": "fillIn",
    },
    "fillIn": {
      "@path": INSTANCES + "/timeOffRequest/fillIn",
      "sling:resourceType": "wf/TaskInstance",
      "label": "Say when you want to be away",
      "status": "created",
      "outcomeOptions": [],
      // The server saying this one waits for whoever is reading. Every task below that a reader is
      // meant to be offered carries it; a task without it is somebody else's
      "@mine": true,
    },
    "checkedBudget": {
      "@path": INSTANCES + "/timeOffRequest/checkedBudget",
      "sling:resourceType": "wf/TaskInstance",
      "label": "Check the budget",
      "status": "completed",
    },
    "approveRequest": {
      "@path": INSTANCES + "/timeOffRequest/approveRequest",
      "sling:resourceType": "wf/TaskInstance",
      "label": "Approve the request",
      "status": "created",
      "outcomeOptions": ["approved", "rejected"],
      "@mine": true,
    },
  },
};

function answering(body: unknown, ok = true, status = 200) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
    () => Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as unknown as Response));
}

describe("fetchOpenTasks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reads the tasks a submission's workflows are still waiting on", async () => {
    const fetchMock = answering(CONTAINER);
    vi.stubGlobal("fetch", fetchMock);

    const tasks = await fetchOpenTasks(PATH);

    expect(fetchMock.mock.calls[0][0]).toBe(INSTANCES + ".deep.json");
    // The completed task is not waiting on anybody, and neither the token nor the variables are
    // tasks at all
    expect(tasks.map(task => task.label))
      .toEqual(["Say when you want to be away", "Approve the request"]);
    expect(tasks[0].path).toBe(INSTANCES + "/timeOffRequest/fillIn");
    expect(tasks[0].outcomeOptions).toEqual([]);
    expect(tasks[1].outcomeOptions).toEqual(["approved", "rejected"]);
  });

  it("reads a lone offered outcome, which arrives as a bare string rather than an array", async () => {
    vi.stubGlobal("fetch", answering({
      "i": {
        "sling:resourceType": "wf/WorkflowInstance",
        "t": {
          "@path": INSTANCES + "/i/t",
          "sling:resourceType": "wf/TaskInstance",
          "label": "Acknowledge",
          "status": "created",
          "outcomeOptions": "acknowledged",
          "@mine": true,
        },
      },
    }));

    expect((await fetchOpenTasks(PATH))[0].outcomeOptions).toEqual(["acknowledged"]);
  });

  it("falls back to a task's node name when it carries no label", async () => {
    vi.stubGlobal("fetch", answering({
      "i": {
        "sling:resourceType": "wf/WorkflowInstance",
        "t": {
          "@path": INSTANCES + "/i/nameless", "sling:resourceType": "wf/TaskInstance",
          "status": "created", "@mine": true,
        },
      },
    }));

    expect((await fetchOpenTasks(PATH))[0].label).toBe("nameless");
  });

  it("skips a task that is not the reader's to do", async () => {
    // Dropped here rather than downstream, so nothing can render a decision that belongs to somebody
    // else — its label discloses what they may do just as plainly as its buttons would
    vi.stubGlobal("fetch", answering({
      "i": {
        "sling:resourceType": "wf/WorkflowInstance",
        "t": {
          "@path": INSTANCES + "/i/theirs", "sling:resourceType": "wf/TaskInstance",
          "label": "Approve the request", "status": "created", "@mine": false,
        },
      },
    }));

    expect(await fetchOpenTasks(PATH)).toEqual([]);
  });

  it("skips a task the server said nothing about", async () => {
    // Fail closed: absent is not permission
    vi.stubGlobal("fetch", answering({
      "i": {
        "sling:resourceType": "wf/WorkflowInstance",
        "t": {
          "@path": INSTANCES + "/i/unmarked", "sling:resourceType": "wf/TaskInstance",
          "label": "Approve the request", "status": "created",
        },
      },
    }));

    expect(await fetchOpenTasks(PATH)).toEqual([]);
  });

  it("skips a task the server would not say where to find", async () => {
    vi.stubGlobal("fetch", answering({
      "i": {
        "sling:resourceType": "wf/WorkflowInstance",
        "t": { "sling:resourceType": "wf/TaskInstance", "label": "Nowhere", "status": "created" },
      },
    }));

    expect(await fetchOpenTasks(PATH)).toEqual([]);
  });

  it("reports nothing to do for a submission no workflow is running on", async () => {
    // No container at all, which is what a submission outside any process looks like
    vi.stubGlobal("fetch", answering({}, false, 404));

    expect(await fetchOpenTasks(PATH)).toEqual([]);
  });

  it("reports a failure to read as one", async () => {
    vi.stubGlobal("fetch", answering({}, false, 500));

    await expect(fetchOpenTasks(PATH)).rejects.toThrow("could not be read (500)");
  });
});

describe("completeTask", () => {
  const TASK = { path: INSTANCES + "/timeOffRequest/fillIn", label: "Send it", outcomeOptions: [] };

  it("posts the completion to the task itself", async () => {
    const post = answering({});

    await completeTask(post, TASK);

    const [url, init] = post.mock.calls[0];
    expect(url).toBe(TASK.path);
    expect(init?.method).toBe("POST");
    // Nothing to decide, so nothing is decided: an outcome would be routed on by a gateway that
    // this task does not lead to
    expect((init?.body as URLSearchParams).toString()).toBe("");
  });

  it("carries a decision when the task is one", async () => {
    const post = answering({});

    await completeTask(post, { ...TASK, outcomeOptions: ["approved", "rejected"] }, "approved");

    expect(((post.mock.calls[0][1]?.body) as URLSearchParams).get("outcome")).toBe("approved");
  });

  it("reports the engine's own refusal", async () => {
    const post = answering({ error: "You are not allowed to do this" }, false, 403);

    await expect(completeTask(post, TASK)).rejects.toThrow("You are not allowed to do this");
  });

  it("falls back to the status when the refusal says nothing", async () => {
    const post = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(
      { ok: false, status: 409, json: () => Promise.reject(new Error("no body")) } as unknown as Response));

    await expect(completeTask(post, TASK)).rejects.toThrow("This could not be done (409)");
  });
});
