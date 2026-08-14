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

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import SubmissionTasks from "@iap/submissions/SubmissionTasks";

type FetchStub = (url: string, init?: RequestInit) => Promise<Response>;

const PATH = "/Submissions/a/b/demo-1";

const TASK = PATH + "/wf:instances/timeOffRequest/fillIn";

// One workflow, parked on the given task
function waitingOn(task: Record<string, unknown>) {
  return {
    timeOffRequest: {
      "@path": PATH + "/wf:instances/timeOffRequest",
      "sling:resourceType": "wf/WorkflowInstance",
      "task": { "sling:resourceType": "wf/TaskInstance", ...task },
    },
  };
}

// A step with nothing to decide, which the reader may complete: a request waiting to be sent
const SEND = {
  "@path": TASK,
  "label": "Say when you want to be away",
  "status": "created",
  "@events": [ "complete" ],
};

// Answers the container read, and whatever the completion is answered with.
//
// Every answer carries a `url`, which the authenticated fetch reads to tell a real response from a
// redirect to the login page.
function repository(container: unknown, completion: Partial<Response> = { ok: true, status: 200 }) {
  return vi.fn<FetchStub>((url, init) => Promise.resolve((init?.method === "POST"
    ? { url, redirected: false, json: () => Promise.resolve({}), ...completion }
    : { url, ok: true, status: 200, json: () => Promise.resolve(container) }) as unknown as Response));
}

const renderTasks = (onCompleted?: () => void) =>
  render(<SubmissionTasks path={PATH} onCompleted={onCompleted} />, { wrapper: NoticeProvider });

describe("SubmissionTasks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers the step the process is waiting on, under its own name", async () => {
    const fetchMock = repository(waitingOn(SEND));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    // The task's label, not a word this component chose: a process that calls sending something
    // else says so here without a line of code changing
    expect(await screen.findByRole("button", { name: /Say when you want to be away/ })).toBeInTheDocument();
    // The container alone, its references left as paths, and the events the reader may send each task
    expect(fetchMock.mock.calls[0][0]).toBe(PATH + "/wf:instances.deep.simple.-dereference.events.json");
  });

  it("completes the task and tells the page, which is what makes it a submit button", async () => {
    const fetchMock = repository(waitingOn(SEND));
    vi.stubGlobal("fetch", fetchMock);
    const completed = vi.fn();

    renderTasks(completed);
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));

    await waitFor(() => expect(completed).toHaveBeenCalled());
    const post = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(post?.[0]).toBe(TASK + ".complete.json");
    // Nothing to decide, so nothing is decided: an outcome would be routed on by a gateway this task
    // does not lead to
    expect(String(post?.[1]?.body)).toBe("");
    // And what it is waiting for is read again, since the step just done is no longer one of them
    expect(fetchMock.mock.calls.filter(call => call[1]?.method !== "POST")).toHaveLength(2);
  });

  it("says what the engine said when it refuses", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(SEND), {
      ok: false,
      status: 403,
      json: () => Promise.resolve({ error: "You are not allowed to do this" }),
    }));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));

    // The engine's own words, not a translation of a status code: it is the definition that
    // refused, and only it knows why
    expect(await screen.findByText("You are not allowed to do this")).toBeInTheDocument();
    expect(screen.getByText("Say when you want to be away did not go through")).toBeInTheDocument();
  });

  it.each([
    // Not disabled, not greyed out, absent: naming a reviewer's step to a submitter discloses what
    // they may do with the request whether or not the control can be pressed
    [ "is not the reader's", { "@events": [] } ],
    // An approval needs somewhere to say why, which belongs with the review screen rather than being
    // smuggled in as two more buttons here
    [ "carries a decision", { outcomeOptions: [ "approved", "rejected" ] } ],
    [ "is already done", { status: "completed" } ],
  ])("offers nothing for a task that %s", async (_case, difference) => {
    const container = waitingOn(SEND);
    vi.stubGlobal("fetch", repository({
      timeOffRequest: {
        ...container.timeOffRequest,
        "other": {
          "sling:resourceType": "wf/TaskInstance",
          ...SEND,
          "@path": PATH + "/wf:instances/timeOffRequest/other",
          "label": "Approve the request",
          ...difference,
        },
      },
    }));

    renderTasks();

    // The step that is offered shows the list has been read, so the one that is not is really absent
    expect(await screen.findByRole("button", { name: /Say when you want/ })).toBeInTheDocument();
    expect(screen.queryByText("Approve the request")).toBeNull();
  });

  it("offers nothing for a submission no workflow is running on", async () => {
    // No container at all, which is what a submission outside any process looks like
    const fetchMock = vi.fn<FetchStub>(url => Promise.resolve(
      { url, ok: false, status: 404, json: () => Promise.resolve({}) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("offers nothing when the answer is not a node at all", async () => {
    const fetchMock = vi.fn<FetchStub>(url => Promise.resolve(
      { url, ok: true, status: 200, json: () => Promise.resolve(null) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("stays quiet when what a request is waiting for cannot be read", async () => {
    // Not being able to read this is a reason to offer nothing, not a reason to put an error across
    // a page that is otherwise perfectly readable
    const fetchMock = vi.fn<FetchStub>(() => Promise.reject(new Error("offline")));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
