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
import { jsonResponse, stubFetch, type FetchRoute } from "@iap/frontend-commons/fetch.fixture";
import type { Enqueue } from "@iap/frontend-commons/useWorkQueue";
import SubmissionTasks from "@iap/submissions/SubmissionTasks";

import { SEND, SEND_LABEL, waitingOn } from "./workflowTasks.fixture";
import { aQueue, settle } from "./workQueue.fixture";

const PATH = "/Submissions/a/b/demo-1";

const TASK = PATH + "/wf:instances/timeOffRequest/fillIn";

// Answers the container read, and whatever the completion is answered with.
const repositoryOf = (container: unknown, completion = jsonResponse({})): FetchRoute =>
  request => request.method === "POST" ? completion.clone() : jsonResponse(container);

const repository = (...args: Parameters<typeof repositoryOf>) => stubFetch(repositoryOf(...args));

// Answers the first read of the container, then fails every read after it, as a re-read can.
function repositoryFailingRereads(container: unknown) {
  let reads = 0;
  return stubFetch(request => {
    if (request.method === "POST") {
      return jsonResponse({});
    }
    reads += 1;
    return reads === 1 ? jsonResponse(container) : jsonResponse({}, 500);
  });
}

const renderTasks = ({ readinessNote, queue = aQueue(), checkReady, blocked, onCompleted }: {
  readinessNote?: string;
  queue?: Enqueue;
  checkReady?: () => Promise<void>;
  blocked?: boolean;
  onCompleted?: () => void;
} = {}) => render(
  <SubmissionTasks path={PATH} readinessNote={readinessNote} queue={queue} checkReady={checkReady} blocked={blocked}
    onCompleted={onCompleted} />,
  { wrapper: NoticeProvider },
);

const posted = (fetchMock: ReturnType<typeof repository>) =>
  fetchMock.mock.calls.filter(call => call[1]?.method === "POST");

describe("SubmissionTasks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers the step the process is waiting on, under its own name", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks();

    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe(PATH + "/wf:instances.deep.-dereference.events.json");
  });

  it("says what still stands in the way of the step", async () => {
    repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks({ readinessNote: "Answer everything first." });

    expect(await screen.findByText("Answer everything first.")).toBeInTheDocument();
  });

  // Whether the step goes through is decided when it is taken, after the answers still being saved
  it("still offers the step while something stands in its way", async () => {
    repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks({ readinessNote: "Answer everything first." });

    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeEnabled();
  });

  // Something still running, such as a document being read, must finish before the step can be taken
  it("holds the step back while it is blocked", async () => {
    vi.stubGlobal("fetch", repository(waitingOn(PATH, { fillIn: SEND })));

    renderTasks({ readinessNote: "Wait until the document has been read.", blocked: true });

    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeDisabled();
    expect(screen.getByText("Wait until the document has been read.")).toBeInTheDocument();
  });

  it("completes the task with no outcome, since it has nothing to decide", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(posted(fetchMock)).toHaveLength(1));
    expect(posted(fetchMock)[0][0]).toBe(TASK + ".complete.json");
    expect(String(posted(fetchMock)[0][1]?.body)).toBe("");
  });

  it("tells the page once the step is done, which is what makes it a submit button", async () => {
    repository(waitingOn(PATH, { fillIn: SEND }));
    const completed = vi.fn();

    renderTasks({ onCompleted: completed });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(completed).toHaveBeenCalled());
  });

  it("reads what the request is waiting for again once the step is done", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(fetchMock.mock.calls.filter(call => call[1]?.method !== "POST")).toHaveLength(2));
  });

  it("checks and completes the step once the work queued before it has settled", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));
    const queue = aQueue();
    const saving = Promise.withResolvers<undefined>();
    const checkReady = vi.fn(() => Promise.resolve());
    void queue(() => saving.promise);

    renderTasks({ queue, checkReady });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));
    expect(checkReady).not.toHaveBeenCalled();
    expect(posted(fetchMock)).toHaveLength(0);

    saving.resolve(undefined);
    await waitFor(() => expect(posted(fetchMock)).toHaveLength(1));
    expect(checkReady).toHaveBeenCalledTimes(1);
  });

  it("holds the work queued after the step until the step is answered", async () => {
    const completion = Promise.withResolvers<Response>();
    const fetchMock = stubFetch(request => request.method === "POST" ? completion.promise : undefined,
      repositoryOf(waitingOn(PATH, { fillIn: SEND })));
    const queue = aQueue();
    const later = vi.fn(() => Promise.resolve());

    renderTasks({ queue });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));
    await waitFor(() => expect(posted(fetchMock)).toHaveLength(1));
    void queue(later);
    await settle();
    expect(later).not.toHaveBeenCalled();

    completion.resolve(jsonResponse({}));
    await waitFor(() => expect(later).toHaveBeenCalledTimes(1));
  });

  it("completes nothing when the submission is not ready for the step", async () => {
    const fetchMock = repository(waitingOn(PATH, { fillIn: SEND }));

    renderTasks({ checkReady: () => Promise.reject(new Error("Answer everything first.")) });
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    expect(await screen.findByText(`${SEND_LABEL} did not go through`)).toBeInTheDocument();
    expect(screen.getByText("Answer everything first.")).toBeInTheDocument();
    expect(posted(fetchMock)).toHaveLength(0);
  });

  it("offers nothing when the read after a step fails, rather than the step just done", async () => {
    repositoryFailingRereads(waitingOn(PATH, { fillIn: SEND }));

    renderTasks();
    await userEvent.click(await screen.findByRole("button", { name: SEND_LABEL }));

    await waitFor(() => expect(screen.queryByRole("button", { name: SEND_LABEL })).toBeNull());
  });

  it("says what the engine said when it refuses", async () => {
    repository(waitingOn(PATH, { fillIn: SEND }), jsonResponse({ error: "You are not allowed to do this" }, 403));

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
    repository(waitingOn(PATH, {
      fillIn: SEND,
      other: { ...SEND, label: "Approve the request", ...difference },
    }));

    renderTasks();

    // The offered step shows that the list has been read, so the other one is really absent
    expect(await screen.findByRole("button", { name: SEND_LABEL })).toBeInTheDocument();
    expect(screen.queryByText("Approve the request")).toBeNull();
  });

  it("offers nothing when the reader cannot read the container", async () => {
    const fetchMock = stubFetch(() => jsonResponse({}, 404));

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("offers nothing when the answer is not a node at all", async () => {
    const fetchMock = stubFetch(() => jsonResponse(null));

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("stays quiet when what a request is waiting for cannot be read", async () => {
    const fetchMock = stubFetch(() => Promise.reject(new Error("offline")));

    renderTasks();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
