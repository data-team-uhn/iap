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

import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useNavigate } from "react-router";

import LoggedErrorView from "@iap/error-tracking/LoggedErrorView";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { jsonResponse, stubFetch, type FetchRoute } from "@iap/frontend-commons/fetch.fixture";

const TRIAGE_DEFINITIONS = [
  { name: "unacknowledged", label: "Needs attention", color: "#c62828", order: 90, category: [ "error-triage" ] },
  { name: "known-issue", label: "Known issue", color: "#ffb300", order: 110, category: [ "error-triage" ] },
];

const FAILURE = {
  "@path": "/LoggedErrors/abc",
  "@name": "abc",
  "sling:resourceType": "err/LoggedFailure",
  "component": "io.uhndata.iap.tags.internal.TagPropagationEditor",
  "operation": "computeTags",
  "occurrences": 7,
  "jcr:created": "2026-08-01T10:00:00.000+00:00",
  "lastOccurrence": "2026-08-20T18:30:00.000+00:00",
  "type": "java.lang.IllegalStateException",
  "stackTrace": "java.lang.IllegalStateException: nope\n\tat Foo.bar(Foo.java:1)",
  "messages": [ "nope" ],
  "subjects": [ "/Submissions/one" ],
  "actors": [ "priya" ],
  "lastContext": "attempt: 3",
  "computedTags": [ "unacknowledged" ],
};

/** Answers the tag vocabulary. */
const triageTags: FetchRoute = request => request.url.includes("/Tags.search.json")
  ? jsonResponse({ tags: TRIAGE_DEFINITIONS, total: TRIAGE_DEFINITIONS.length })
  : undefined;

/** Answers the tag vocabulary, the error read, and the acknowledge POST. */
function serve(options: {
  error?: unknown;
  errorStatus?: number;
  acknowledge?: { status: number; body: unknown };
} = {}) {
  return stubFetch(triageTags, request => {
    if (request.method === "POST") {
      const answer = options.acknowledge ?? { status: 200, body: { status: "ok" } };
      return jsonResponse(answer.body, answer.status);
    }
    return jsonResponse(options.error ?? FAILURE, options.errorStatus ?? 200);
  });
}

/** A control for driving the one thing MemoryRouter's initialEntries cannot: navigating. */
function Navigator({ to }: { to: string }) {
  const navigate = useNavigate();
  return <button type="button" onClick={() => { void navigate(to); }}>go</button>;
}

const view = (route = "/admin/errors/abc") => render(
  <MemoryRouter initialEntries={[ route ]}>
    <LoggedErrorView />
  </MemoryRouter>,
  { wrapper: NoticeProvider },
);

/** The reads of the error itself, which is what a re-read after a decision adds to. */
const reads = (fetchMock: { mock: { calls: [string, RequestInit?][] } }) =>
  fetchMock.mock.calls.filter(call => call[0].includes(".1.json"));

describe("LoggedErrorView", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("says so, and asks for nothing, when the address names no single error", async () => {
    const fetchMock = serve();
    view("/admin/errors");

    expect(await screen.findByText("This address does not name a recorded error.")).toBeInTheDocument();
    expect(reads(fetchMock)).toHaveLength(0);
  });

  it("shows everything the instance kept about a thrown failure", async () => {
    serve();
    view();

    // The heading is queried by text, not by role: the page title is a Typography variant, and the
    // theme decides which element it renders as
    expect(await screen.findByText("IllegalStateException")).toBeInTheDocument();
    expect(screen.getByText("java.lang.IllegalStateException")).toBeInTheDocument();
    expect(screen.getByText("io.uhndata.iap.tags.internal.TagPropagationEditor")).toBeInTheDocument();
    expect(screen.getByText("computeTags")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("nope")).toBeInTheDocument();
    expect(screen.getByText("/Submissions/one")).toBeInTheDocument();
    expect(screen.getByText("priya")).toBeInTheDocument();
    expect(screen.getByText(/attempt: 3/)).toBeInTheDocument();
    expect(screen.getByText(/at Foo\.bar/)).toBeInTheDocument();
    expect(screen.getByText("Something was thrown")).toBeInTheDocument();
    // The label of the triage tag's definition
    expect(await screen.findByText("Needs attention")).toBeInTheDocument();
  });

  it("shows a problem without pretending it has a stack trace", async () => {
    serve({
      error: {
        "@name": "def",
        "sling:resourceType": "err/LoggedProblem",
        "problem": "unknown comparator",
        "occurrences": 2,
        "lastOccurrence": "2026-08-20T18:30:00.000+00:00",
        "computedTags": [ "unacknowledged" ],
      },
    });
    view("/admin/errors/def");

    expect(await screen.findByText("unknown comparator")).toBeInTheDocument();
    expect(screen.getByText("Nothing was thrown")).toBeInTheDocument();
    expect(screen.queryByText("Stack trace")).not.toBeInTheDocument();
  });

  it("omits the samples the server kept none of, rather than showing empty headings", async () => {
    serve({
      error: { ...FAILURE, messages: [], subjects: [], actors: [], lastContext: undefined },
    });
    view();

    await screen.findByText("java.lang.IllegalStateException");
    expect(screen.queryByText("Messages")).not.toBeInTheDocument();
    expect(screen.queryByText("Subjects")).not.toBeInTheDocument();
    expect(screen.queryByText("Acting for")).not.toBeInTheDocument();
    expect(screen.queryByText("Context of the last occurrence")).not.toBeInTheDocument();
  });

  it("shows a dash for a timestamp the error does not carry", async () => {
    serve({
      error: {
        "@name": "abc",
        "sling:resourceType": "err/LoggedProblem",
        "problem": "unknown comparator",
        "occurrences": 1,
      },
    });
    view();

    await screen.findByText("unknown comparator");
    // The component, the operation and both timestamps
    expect(screen.getAllByText("—")).toHaveLength(4);
  });

  it("heads the page with the fingerprint when the error names neither a phrase nor a throwable", async () => {
    serve({
      error: { "@name": "abc", "sling:resourceType": "err/LoggedFailure", occurrences: 1 },
    });
    view();

    expect(await screen.findAllByText("abc")).not.toHaveLength(0);
  });

  it("credits a decision whose author was not recorded without leaving a gap", async () => {
    serve({
      error: {
        ...FAILURE,
        anon: {
          "@name": "anon",
          "sling:resourceType": "err/Acknowledgement",
          "resolution": "known-issue",
          "acknowledgedOccurrences": 1,
          "jcr:created": "2026-08-02T10:00:00.000+00:00",
        },
      },
    });
    view();

    expect(await screen.findByText(/somebody ·/)).toBeInTheDocument();
  });

  it("reports a rejection that is not an Error at all", async () => {
    stubFetch(triageTags, () => Promise.reject("network is down"));
    view();

    expect(await screen.findByText("The recorded error could not be read")).toBeInTheDocument();
  });

  it("drops a read that lands after the reader has moved to another error", async () => {
    let releaseFirst!: (response: Response) => void;
    stubFetch(triageTags, request => request.url.includes("/abc.1.json")
      ? new Promise<Response>(resolve => { releaseFirst = resolve; })
      : jsonResponse({
        "@name": "def",
        "sling:resourceType": "err/LoggedProblem",
        "problem": "the second fault",
        "occurrences": 1,
      }));

    render(
      <MemoryRouter initialEntries={[ "/admin/errors/abc" ]}>
        <Navigator to="/admin/errors/def" />
        <LoggedErrorView />
      </MemoryRouter>,
      { wrapper: NoticeProvider },
    );
    await waitFor(() => { expect(releaseFirst).toBeDefined(); });

    await userEvent.click(screen.getByRole("button", { name: "go" }));
    expect(await screen.findByText("the second fault")).toBeInTheDocument();

    // The first error's read comes back only now
    await act(async () => {
      releaseFirst(jsonResponse(FAILURE));
      await new Promise(resolve => { setTimeout(resolve, 0); });
    });

    expect(screen.getByText("the second fault")).toBeInTheDocument();
    expect(screen.queryByText("java.lang.IllegalStateException")).not.toBeInTheDocument();
  });

  it("reports a read it could not make, and retries it", async () => {
    let fail = true;
    stubFetch(triageTags, () => {
      const response = fail ? jsonResponse({}, 403) : jsonResponse(FAILURE);
      fail = false;
      return response;
    });
    view();

    expect(await screen.findByText("The recorded error could not be read")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /retry|try again/i }));

    expect(await screen.findByText("java.lang.IllegalStateException")).toBeInTheDocument();
  });

  it("lists the decisions taken, newest first, with who and why", async () => {
    serve({
      error: {
        ...FAILURE,
        first: {
          "@name": "first",
          "sling:resourceType": "err/Acknowledgement",
          "resolution": "acknowledged",
          "acknowledgedOccurrences": 1,
          "jcr:created": "2026-08-02T10:00:00.000+00:00",
          "jcr:createdBy": "sam",
        },
        second: {
          "@name": "second",
          "sling:resourceType": "err/Acknowledgement",
          "resolution": "known-issue",
          "note": "waiting on the upstream fix",
          "acknowledgedOccurrences": 5,
          "jcr:created": "2026-08-10T10:00:00.000+00:00",
          "jcr:createdBy": "priya",
        },
      },
    });
    view();

    expect(await screen.findByText("Decisions (2)")).toBeInTheDocument();
    expect(screen.getByText("waiting on the upstream fix")).toBeInTheDocument();
    // Matched with the separator after the author, because "priya" is also one of the actors
    expect(screen.getByText(/priya ·/)).toBeInTheDocument();
    expect(screen.getByText(/sam ·/)).toBeInTheDocument();
    // Newest first: the known-issue decision was taken after five occurrences
    const labels = screen.getAllByText(/^(Known issue|Acknowledged)$/).map(node => node.textContent);
    expect(labels).toEqual([ "Known issue", "Acknowledged" ]);
  });

  it("says plainly when nobody has decided anything yet", async () => {
    serve();
    view();

    expect(await screen.findByText("Nobody has recorded a decision about this yet.")).toBeInTheDocument();
  });

  it("records a decision and re-reads, because the markers are derived server-side", async () => {
    const fetchMock = serve();
    view();
    await screen.findByText("java.lang.IllegalStateException");
    expect(reads(fetchMock)).toHaveLength(1);

    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(await screen.findByText("Decision recorded")).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(post?.[0]).toBe("/LoggedErrors/abc.acknowledge.json");
    await waitFor(() => { expect(reads(fetchMock)).toHaveLength(2); });
  });

  it("sends the decision that was chosen and the note, then clears the note", async () => {
    const fetchMock = serve();
    view();
    await screen.findByText("java.lang.IllegalStateException");
    const note = screen.getByRole("textbox", { name: /Why/ });

    await userEvent.click(screen.getByRole("combobox", { name: "Decision" }));
    await userEvent.click(within(screen.getByRole("listbox")).getByText(/Known issue/));
    await userEvent.type(note, "upstream bug");
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await screen.findByText("Decision recorded");
    const post = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(post?.[1]?.body).toBe("resolution=known-issue&note=upstream+bug");
    await waitFor(() => { expect(note).toHaveValue(""); });
  });

  it("reports a refused decision in the server's words, and does not re-read", async () => {
    const fetchMock = serve({
      acknowledge: { status: 400, body: { status: "error", error: "resolution must name one of the error-triage tags" } },
    });
    view();
    await screen.findByText("java.lang.IllegalStateException");

    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(await screen.findByText("The decision was not recorded")).toBeInTheDocument();
    expect(screen.getByText("resolution must name one of the error-triage tags")).toBeInTheDocument();
    expect(reads(fetchMock)).toHaveLength(1);
  });

  it("offers another attempt only when the server could not carry it out", async () => {
    serve({
      acknowledge: { status: 500, body: { status: "error", error: "Could not record the decision" } },
    });
    view();
    await screen.findByText("java.lang.IllegalStateException");

    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    const notice = await screen.findByRole("alert");
    // toHaveTextContent, not getByText: the message is a bare text node beside the title
    expect(notice).toHaveTextContent("Could not record the decision");
    expect(within(notice).getByRole("button", { name: /retry|try again/i })).toBeInTheDocument();
  });

  it("does not offer another attempt for a decision the error cannot carry", async () => {
    serve({
      acknowledge: { status: 400, body: { status: "error", error: "not a triage tag" } },
    });
    view();
    await screen.findByText("java.lang.IllegalStateException");

    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    const notice = await screen.findByRole("alert");
    expect(within(notice).queryByRole("button", { name: /retry|try again/i })).not.toBeInTheDocument();
  });
});
