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

import { SEND, SEND_LABEL, waitingOn } from "./workflowTasks.fixture";

type FetchStub = (url: string, init?: RequestInit) => Promise<Response>;

const PATH = "/Submissions/a/b/demo-1";

const TASK = PATH + "/wf:instances/timeOffRequest/fillIn";

// Answers the container read, and whatever the completion is answered with.
//
// Every answer carries a `url`, which the authenticated fetch reads to tell a real response from a
// redirect to the login page.
function repository(container: unknown, completion: Partial<Response> = { ok: true, status: 200 }) {
  return vi.fn<FetchStub>((url, init) => Promise.resolve((init?.method === "POST"
    ? { url, redirected: false, json: () => Promise.resolve({}), ...completion }
    : { url, ok: true, status: 200, json: () => Promise.resolve(container) }) as unknown as Response));
}

// Answers the first read of the container, then fails every read after it, as a re-read can.
function repositoryFailingRereads(container: unknown) {
  let reads = 0;
  return vi.fn<FetchStub>((url, init) => {
    if (init?.method === "POST") {
      return Promise.resolve(
        { url, redirected: false, ok: true, status: 200, json: () => Promise.resolve({}) } as unknown as Response);
    }
    reads += 1;
    return Promise.resolve((reads === 1
      ? { url, ok: true, status: 200, json: () => Promise.resolve(container) }
      : { url, ok: false, status: 500, json: () => Promise.resolve({}) }) as unknown as Response);
  });
}

const renderTasks = ({ blockedReason, beforeComplete, onCompleted }: {
  blockedReason?: string;
  beforeComplete?: () => Promise<void>;
  onCompleted?: () => void;
} = {}) => render(
  <SubmissionTasks path={PATH} blockedReason={blockedReason} beforeComplete={beforeComplete}
    onCompleted={onCompleted} />,
  { wrapper: NoticeProvider },
);

const posted = (fetchMock: ReturnType<typeof repository>) =>
  fetchMock.mock.calls.filter(call => call[1]?.method === "POST");

describe("SubmissionTasks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers the step the process is waiting on, under its own name", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe(PATH + "/wf:instances.deep.-dereference.events.json");
  });

  it("holds the step back while the submission is not ready, and says why", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(PATH, { fillIn: SEND })));

    renderTasks({ blockedReason: "Answer everything first." });
    const send = await screen.findByRole("button", { name: SEND_LABEL });
    await userEvent.hover(send.parentElement!);

    expect(send).toBeDisabled();
    expect(await screen.findByRole("tooltip", { name: "Answer everything first." })).toBeInTheDocument();
  });

  it("completes the task with no outcome, since it has nothing to decide", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(posted(fetchMock)).toHaveLength(1));
    expect(posted(fetchMock)[0][0]).toBe(TASK + ".complete.json");
    expect(String(posted(fetchMock)[0][1]?.body)).toBe("");
  });

  it("tells the page once the step is done, which is what makes it a submit button", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(PATH, { fillIn: SEND })));
    const completed = vi.fn();

    renderTasks({ onCompleted: completed });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(completed).toHaveBeenCalled());
  });

  it("reads what the request is waiting for again once the step is done", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(fetchMock.mock.calls.filter(call => call[1]?.method !== "POST")).toHaveLength(2));
  });

  it("waits for the answers still being saved before completing the step", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    vi.stubGlobal("fetch", fetchMock);
    const saving = Promise.withResolvers<undefined>();

    renderTasks({ beforeComplete: () => saving.promise });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));
    expect(posted(fetchMock)).toHaveLength(0);

    saving.resolve(undefined);
    await waitFor(() => expect(posted(fetchMock)).toHaveLength(1));
  });

  it("completes nothing when an answer could not be saved", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks({ beforeComplete: () => Promise.reject(new Error("The answer was refused")) });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    expect(await screen.findByText(`${SEND_LABEL} did not go through`)).toBeInTheDocument();
    expect(posted(fetchMock)).toHaveLength(0);
  });

  it("offers nothing when the read after a step fails, rather than the step just done", async () => {
    vi.stubGlobal("fetch", repositoryFailingRereads(waitingOn(PATH, { fillIn: SEND })));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(screen.queryByRole("button", { name: SEND_LABEL })).toBeNull());
  });

  it("says what the engine said when it refuses", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(PATH, { fillIn: SEND }), {
      ok: false,
      status: 403,
      json: () => Promise.resolve({ error: "You are not allowed to do this" }),
    }));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    expect(await screen.findByText("You are not allowed to do this")).toBeInTheDocument();
    expect(screen.getByText(`${SEND_LABEL} did not go through`)).toBeInTheDocument();
  });

  it.each([
    [ "is not the reader's", { "@events": [] } ],
    [ "carries a decision", { outcomeOptions: [ "approved", "rejected" ] } ],
    [ "is already done", { status: "completed" } ],
  ])("offers nothing for a task that %s", async (_case, difference) => {
    vi.stubGlobal("fetch", repository(waitingOn(PATH, {
      fillIn: SEND,
      other: { ...SEND, label: "Approve the request", ...difference },
    })));

    renderTasks();

    // The step that is offered shows the list has been read, so the one that is not is really absent
    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeInTheDocument();
    expect(screen.queryByText("Approve the request")).toBeNull();
  });

  it("offers nothing when the reader cannot read the container", async () => {
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
    const fetchMock = vi.fn<FetchStub>(() => Promise.reject(new Error("offline")));
    vi.stubGlobal("fetch", fetchMock);

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
