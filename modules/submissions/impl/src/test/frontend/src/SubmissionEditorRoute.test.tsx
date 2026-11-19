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

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import type { PdfViewerProps } from "@iap/documents-pdf-viewer/PdfViewer";
import { loadAnswerComponents } from "@iap/submissions/answers";
import "@iap/submissions/answers/BooleanAnswer";
import "@iap/submissions/answers/TextAnswer";
import { loadRequirementComponents } from "@iap/submissions/requirements";
import SubmissionEditor from "@iap/submissions/SubmissionEditor";
import type { SubmissionForm } from "@iap/submissions/submissionForm";
import { COMPLETE_EVENT, type SubmissionTask } from "@iap/submissions/taskModel";

import {
  GENERATED,
  PROPOSAL,
  SUBMISSION,
  WRITTEN,
  attaching,
  protocol,
  questionnaire,
  routeForm,
} from "./route.fixture";
import { aQueue } from "./workQueue.fixture";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

// PDF.js does not run here; the viewer says which file it was given
vi.mock("@iap/documents-pdf-viewer/PdfViewer", () => ({
  default: ({ url }: PdfViewerProps) => <p>{`Viewing ${url}`}</p>,
}));

beforeEach(async () => {
  await loadRequirementComponents();
  await loadAnswerComponents();
});

afterEach(() => vi.unstubAllGlobals());

const INSTANCE = `${SUBMISSION}/wf:instances/researchProposal`;

function task(name: string, requirement?: string): SubmissionTask {
  return {
    path: `${INSTANCE}/${name}`, label: name, outcomeOptions: [], "@events": [ COMPLETE_EVENT ],
    ...(requirement === undefined ? {} : { requirement }),
  };
}

const CHOOSE = task("chooseRoute", "protocol");
const UPLOAD = task("uploadProposal", "proposal");
const FILL = task("fillQuestionnaire", "questionnaire");
const SEND = task("sendWithoutProtocol");

const waitingOn = (...tasks: SubmissionTask[]) => ({ tasks, opening: "protocol" });

function respond(body: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400, status, url: "", redirected: false, json: () => Promise.resolve(body),
  } as unknown as Response);
}

// Serves each read of the form in turn, and accepts every post but the one to `refused`, which the
// engine refuses with its reason
function serving(reads: SubmissionForm[], refused?: string) {
  let read = 0;
  return vi.fn((url: string, options?: { method?: string }) => {
    if (options?.method === "POST") {
      return url === refused ? respond({ error: "Not now" }, 409) : respond({});
    }
    const next = reads[Math.min(read, reads.length - 1)];
    read += 1;
    return respond(next);
  });
}

const postsOf = (fetchMock: ReturnType<typeof serving>) => fetchMock.mock.calls
  .filter(([ , options ]) => options?.method === "POST")
  .map(([ url ]) => url);

describe("SubmissionEditor on a request that first asks whether there is a protocol", () => {
  it("asks that first, and goes on only once it is answered", async () => {
    const fetchMock = serving([ routeForm({ requirements: [ protocol() ] }), routeForm({ requirements: [ protocol([ "true" ]) ] }) ]);
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(CHOOSE)} />);

    expect(await screen.findByRole("button", { name: "Next" })).toBeDisabled();
    expect(screen.getByText("Answer the required questions to go on.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Yes" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Next" })).toBeEnabled());
    // Answering the first step only saves; Next is what takes the way
    expect(postsOf(fetchMock)).toEqual([ `${SUBMISSION}.save.json` ]);

    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => expect(postsOf(fetchMock)).toContain(`${CHOOSE.path}.complete.json`));
    expect(await screen.findByText("Going on to the next step…")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Yes" })).toBeDisabled();
  });

  it("says why a step did not go through", async () => {
    vi.stubGlobal("fetch", serving([ routeForm() ], `${CHOOSE.path}.complete.json`));

    render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(CHOOSE)} />);
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    expect(await screen.findByText("Not now")).toBeInTheDocument();
  });

  describe("without a protocol", () => {
    const answered = routeForm({ requirements: [ protocol([ "false" ]), questionnaire([ "A study" ]), GENERATED ] });
    const written = routeForm({
      requirements: [ protocol([ "false" ]), questionnaire([ "A study" ]), attaching(GENERATED, WRITTEN) ],
    });

    it("shows the questionnaire, and goes on only once it is answered", async () => {
      vi.stubGlobal("fetch", serving([ routeForm({ requirements: [ protocol([ "false" ]), questionnaire(), GENERATED ] }) ]));

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(FILL)} />);

      expect(await screen.findByLabelText(/What is the study called/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
      // Next writes the proposal, so there is nothing to show of it yet
      expect(screen.queryByText("GeneratedProposal")).toBeNull();
    });

    it("shows the proposal written from the answers, to download and send", async () => {
      const fetchMock = serving([ answered, written ]);
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(FILL)}
        sendControls={<button type="button">Send for review</button>} />);
      await userEvent.click(await screen.findByRole("button", { name: "Next" }));

      const dialog = await screen.findByRole("dialog", { name: "Your proposal" });
      expect(postsOf(fetchMock)).toEqual([ `${FILL.path}.complete.json` ]);
      expect(within(dialog).getByText(`Viewing ${WRITTEN.path}`)).toBeInTheDocument();
      expect(within(dialog).getByRole("link", { name: "PDF" })).toHaveAttribute("download", "Research plan.pdf");
      expect(within(dialog).getByRole("link", { name: "Word" })).toHaveAttribute("download", "Research plan.docx");
      expect(within(dialog).getByRole("button", { name: "Send for review" })).toBeInTheDocument();

      await userEvent.click(within(dialog).getByRole("button", { name: "Keep editing" }));

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("shows everything at the step that sends the request", async () => {
      vi.stubGlobal("fetch", serving([ written ]));

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(SEND)} />);

      expect(await screen.findByText("GeneratedProposal")).toBeInTheDocument();
      expect(screen.getByLabelText(/What is the study called/)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
    });

    it("takes the other way when the answer changes, by completing the step that is open", async () => {
      const fetchMock = serving([ written ]);
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(SEND)} />);
      await userEvent.click(await screen.findByRole("radio", { name: "Yes" }));

      await waitFor(() => expect(postsOf(fetchMock))
        .toEqual([ `${SUBMISSION}.save.json`, `${SEND.path}.complete.json` ]));
    });

    it("keeps the way when the changed answer could not be saved", async () => {
      const fetchMock = serving([ written ], `${SUBMISSION}.save.json`);
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(SEND)} />);
      await userEvent.click(await screen.findByRole("radio", { name: "Yes" }));

      expect(await screen.findByText("Not saved")).toBeInTheDocument();
      expect(postsOf(fetchMock)).toEqual([ `${SUBMISSION}.save.json` ]);
    });

    it("says why the way could not change", async () => {
      vi.stubGlobal("fetch", serving([ written ], `${SEND.path}.complete.json`));

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(SEND)} />);
      await userEvent.click(await screen.findByRole("radio", { name: "Yes" }));

      expect(await screen.findByText("Not now")).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Close" }));
      expect(screen.queryByText("Not now")).toBeNull();
    });
  });

  describe("with a protocol", () => {
    const uploaded = routeForm({
      readsDocuments: true,
      requirements: [ protocol([ "true" ]), attaching(PROPOSAL, { title: "protocol.pdf", path: "/p" }) ],
    });

    it("completes the step waiting for the upload, which has the workflow read it", async () => {
      const fetchMock = serving([ uploaded ]);
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(UPLOAD)} />);
      await userEvent.click(await screen.findByRole("button", { name: "Next" }));

      await waitFor(() => expect(postsOf(fetchMock)).toEqual([ `${UPLOAD.path}.complete.json` ]));
    });

    it("sends the upload to be read when no step waits for it", async () => {
      const fetchMock = serving([ uploaded ]);
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(task("sendWithProtocol"))} />);
      await userEvent.click(await screen.findByRole("button", { name: "Next" }));

      await waitFor(() => expect(postsOf(fetchMock)).toEqual([ `${SUBMISSION}.retryParse.json` ]));
    });

    // Only completing a step makes the workflow read the answer again
    it("keeps the answer as it is while no step of the submitter's is open", async () => {
      vi.stubGlobal("fetch", serving([ uploaded ]));

      render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn()} />);

      expect(await screen.findByRole("radio", { name: "Yes" })).toBeDisabled();
    });
  });

  it("shows a request that can no longer change as it is, with no steps", async () => {
    vi.stubGlobal("fetch", serving([ routeForm({ editable: false, requirements: [ protocol([ "false" ]), questionnaire() ] }) ]));

    render(<SubmissionEditor path={SUBMISSION} queueSave={aQueue()} steps={waitingOn(CHOOSE)} />);

    expect(await screen.findByLabelText(/What is the study called/)).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
  });
});
