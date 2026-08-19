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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import SubmissionTasks from "@iap/submissions/SubmissionTasks";

const PATH = "/Submissions/a/b/demo-1";

const TASK = PATH + "/wf:instances/timeOffRequest/fillIn";

// One workflow, parked on a step with nothing to decide — a request waiting to be sent
function waitingOn(task: Record<string, unknown>) {
  return {
    timeOffRequest: {
      "@path": PATH + "/wf:instances/timeOffRequest",
      "sling:resourceType": "wf/WorkflowInstance",
      "task": { "sling:resourceType": "wf/TaskInstance", ...task },
    },
  };
}

const SEND = {
  "@path": TASK,
  "label": "Say when you want to be away",
  "status": "created",
};

// Answers the container read, and whatever the completion POST is answered with.
//
// Every answer carries a `url`, which useAuthenticatedFetch reads to tell a real response from
// Sling's redirect to the login page — a mock without one is not a response it can classify.
function repository(container: unknown, completion: Partial<Response> = { ok: true }) {
  return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url, init) =>
    Promise.resolve(init?.method === "POST"
      ? ({ url, status: 200, json: () => Promise.resolve({}), ...completion } as unknown as Response)
      : ({ url, ok: true, status: 200, json: () => Promise.resolve(container) } as unknown as Response)));
}

describe("SubmissionTasks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers the step the process is waiting on, under its own name", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(SEND)));

    render(<SubmissionTasks path={PATH} />);

    // The task's label, not a word this component chose: a process that calls sending something
    // else says so here without a line of code changing
    expect(await screen.findByRole("button", { name: /Say when you want to be away/ })).toBeInTheDocument();
  });

  it("refuses to offer the step while the request is not ready to be sent", async () => {
    // The one thing this control decides for itself, and it is not about who may act: whether there is
    // anything left to answer, which the save workflow has already worked out
    const fetchMock = repository(waitingOn(SEND));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionTasks path={PATH} blockedReason="Answer everything this request asks for." />);
    const button = await screen.findByRole("button", { name: /Say when you want/ });

    expect(button).toBeDisabled();
    // The reason has to be reachable: a disabled button fires no events, so it hangs on the wrapper
    fireEvent.mouseOver(button.parentElement!);
    expect(await screen.findByRole("tooltip"))
      .toHaveTextContent("Answer everything this request asks for.");
    // And nothing was sent
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toHaveLength(0);
  });

  it("completes the task and tells the page, which is what makes it a submit button", async () => {
    const fetchMock = repository(waitingOn(SEND));
    vi.stubGlobal("fetch", fetchMock);
    const completed = vi.fn();

    render(<SubmissionTasks path={PATH} onCompleted={completed} />);
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));

    await waitFor(() => expect(completed).toHaveBeenCalled());
    const post = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(post?.[0]).toBe(TASK);
  });

  it("says what the engine said when it refuses", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(SEND), {
      ok: false,
      status: 403,
      json: () => Promise.resolve({ error: "You are not allowed to do this" }),
    }));

    render(<SubmissionTasks path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));

    // The engine's own words, not a translation of a status code: it is the definition that
    // refused, and only it knows why
    expect(await screen.findByText("You are not allowed to do this")).toBeInTheDocument();
  });

  it("says something even when the failure was not an Error", async () => {
    // A rejection need not be an Error, and reporting "undefined" would tell the submitter nothing
    // about a step that did not happen
    vi.stubGlobal("fetch", vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url, init) =>
      init?.method === "POST"
        ? Promise.reject("the network went away")
        : Promise.resolve({ url, ok: true, status: 200,
          json: () => Promise.resolve(waitingOn(SEND)) } as unknown as Response)));

    render(<SubmissionTasks path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));

    expect(await screen.findByText("the network went away")).toBeInTheDocument();
  });

  it("offers nothing for a task that carries a decision", async () => {
    // An approval needs somewhere to say why, which belongs with the review screen rather than
    // being smuggled in as two more buttons here
    vi.stubGlobal("fetch", repository(waitingOn({
      ...SEND, label: "Approve the request", outcomeOptions: ["approved", "rejected"],
    })));

    const { container } = render(<SubmissionTasks path={PATH} />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("offers nothing when nothing is waiting", async () => {
    vi.stubGlobal("fetch", repository(waitingOn({ ...SEND, status: "completed" })));

    const { container } = render(<SubmissionTasks path={PATH} />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("stays quiet when what a request is waiting for cannot be read", async () => {
    // Not being able to read this is a reason to offer nothing, not a reason to put an error
    // across a page that is otherwise perfectly readable
    vi.stubGlobal("fetch", vi.fn<() => Promise<Response>>(() => Promise.reject(new Error("offline"))));

    const { container } = render(<SubmissionTasks path={PATH} />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("lets a refusal be dismissed", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(SEND), {
      ok: false, status: 500, json: () => Promise.resolve({ error: "Something broke" }),
    }));

    render(<SubmissionTasks path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: /Say when you want/ }));
    await userEvent.click(await screen.findByRole("button", { name: "Close" }));

    expect(screen.queryByText("Something broke")).toBeNull();
  });
});
