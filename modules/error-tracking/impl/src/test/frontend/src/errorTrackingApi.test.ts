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
  acknowledgeError,
  errorNameFromRoute,
  errorRoute,
  fetchLoggedError,
  fetchTriageCounts,
  resolutionLabel,
} from "@iap/error-tracking/errorTrackingApi";

// A real Response rather than an object literal: the session-aware fetch reads `response.url` to
// tell a real answer from Sling's redirect to the login page, and a hand-rolled stand-in without
// one throws from inside the hook.
const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

const page = (totalrows: number, approximate = false) => ({
  rows: [],
  offset: 0,
  limit: 1,
  returnedrows: 0,
  totalrows,
  totalIsApproximate: approximate,
});

describe("errorNameFromRoute", () => {
  it("names the error a console route addresses", () => {
    expect(errorNameFromRoute("/admin/errors/a1b2c3")).toBe("a1b2c3");
  });

  it("ignores a trailing slash", () => {
    expect(errorNameFromRoute("/admin/errors/a1b2c3/")).toBe("a1b2c3");
  });

  it("names nothing for the browse page itself", () => {
    // The browse page and one error are different screens; reading the former as an error would
    // send a request for a node that cannot exist
    expect(errorNameFromRoute("/admin/errors")).toBeNull();
    expect(errorNameFromRoute("/admin/errors/")).toBeNull();
  });

  it("names nothing for a route deeper than one error", () => {
    expect(errorNameFromRoute("/admin/errors/a1b2c3/decision")).toBeNull();
  });

  it("names nothing for an unrelated route", () => {
    expect(errorNameFromRoute("/admin/archive/xyz")).toBeNull();
  });

  it("round-trips with errorRoute", () => {
    expect(errorNameFromRoute(errorRoute("deadbeef"))).toBe("deadbeef");
  });
});

describe("resolutionLabel", () => {
  it("labels the decisions a person can take", () => {
    expect(resolutionLabel("known-issue")).toBe("Known issue");
    expect(resolutionLabel("wont-fix")).toBe("Won't fix");
    expect(resolutionLabel("acknowledged")).toBe("Acknowledged");
  });

  it("labels the derived marker, which is not a decision anybody takes", () => {
    expect(resolutionLabel("unacknowledged")).toBe("Needs attention");
  });

  it("falls back to the raw name for a marker it does not know", () => {
    // A deployment may add a triage tag of its own; showing its name beats showing nothing
    expect(resolutionLabel("escalated")).toBe("escalated");
  });
});

describe("fetchTriageCounts", () => {
  // Each call must get its OWN Response: a body can only be read once, and both requests go
  // through the same fetch
  const answering = (needing: number, total: number, approximate = false) => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(
      jsonResponse(200, url.includes("fieldValue=unacknowledged")
        ? page(needing, approximate)
        : page(total, approximate))));
    return fetchMock;
  };

  it("counts what needs attention and what there is in total", async () => {
    const counts = await fetchTriageCounts(answering(3, 41));
    expect(counts.needingAttention).toBe(3);
    expect(counts.total).toBe(41);
    expect(counts.approximate).toBe(false);
  });

  it("asks the errors' own homepage, filtering on the derived triage marker", async () => {
    const fetchMock = answering(0, 0);
    await fetchTriageCounts(fetchMock);

    const urls = fetchMock.mock.calls.map(call => call[0]);
    expect(urls).toHaveLength(2);
    expect(urls.every(url => url.startsWith("/LoggedErrors.paginate.json?"))).toBe(true);
    // There is deliberately no summary endpoint: the homepage is an data:EntityHomepage, so the
    // pagination servlet already answers this
    const filtered = urls.find(url => url.includes("fieldName=computedTags"));
    expect(filtered).toContain("fieldValue=unacknowledged");
  });

  it("reports the counts as lower bounds when either scan stopped at the bound", async () => {
    expect((await fetchTriageCounts(answering(1, 10000, true))).approximate).toBe(true);
  });

  it("fails when the errors cannot be read at all", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(new Response("", { status: 404 })));
    await expect(fetchTriageCounts(fetchMock)).rejects.toThrow();
  });
});

describe("fetchLoggedError", () => {
  const failure = {
    "@path": "/LoggedErrors/abc",
    "@name": "abc",
    "sling:resourceType": "err/LoggedFailure",
    component: "io.uhndata.iap.tags.internal.TagPropagationEditor",
    operation: "computeTags",
    occurrences: 7,
    "jcr:created": "2026-08-01T10:00:00.000+00:00",
    lastOccurrence: "2026-08-20T18:30:00.000+00:00",
    type: "java.lang.IllegalStateException",
    stackTrace: "java.lang.IllegalStateException: nope\n\tat Foo.bar(Foo.java:1)",
    messages: [ "nope", "still nope" ],
    subjects: [ "/Submissions/1" ],
    computedTags: [ "unacknowledged" ],
  };

  it("reads a thrown failure", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, failure)));
    const error = await fetchLoggedError(fetchMock, "abc");

    expect(error.kind).toBe("failure");
    expect(error.type).toBe("java.lang.IllegalStateException");
    expect(error.occurrences).toBe(7);
    expect(error.messages).toEqual([ "nope", "still nope" ]);
    expect(error.triage).toEqual([ "unacknowledged" ]);
    expect(error.decisions).toEqual([]);
  });

  it("asks for one level of children, which is where the decisions are", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, failure)));
    await fetchLoggedError(fetchMock, "abc");
    expect(fetchMock.mock.calls[0][0]).toBe("/LoggedErrors/abc.1.json");
  });

  it("reads a problem, which carries a phrase instead of a stack trace", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      "@name": "def",
      "sling:resourceType": "err/LoggedProblem",
      problem: "unknown comparator",
      occurrences: 1,
      lastOccurrence: "2026-08-20T18:30:00.000+00:00",
    })));
    const error = await fetchLoggedError(fetchMock, "def");

    expect(error.kind).toBe("problem");
    expect(error.problem).toBe("unknown comparator");
    expect(error.stackTrace).toBeUndefined();
  });

  it("accepts a single-valued sample as the bare string the serializer sends", async () => {
    // A multi-valued property holding one value round-trips as a string, not a one-element array
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure, messages: "only one", computedTags: "acknowledged",
    })));
    const error = await fetchLoggedError(fetchMock, "abc");

    expect(error.messages).toEqual([ "only one" ]);
    expect(error.triage).toEqual([ "acknowledged" ]);
  });

  it("orders the decisions newest first, by how much had happened when each was taken", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure,
      decision: {
        "@name": "decision",
        "sling:resourceType": "err/Acknowledgement",
        resolution: "acknowledged",
        acknowledgedOccurrences: 1,
        "jcr:created": "2026-08-02T10:00:00.000+00:00",
      },
      decision_2: {
        "@name": "decision_2",
        "sling:resourceType": "err/Acknowledgement",
        resolution: "known-issue",
        note: "waiting on the upstream fix",
        acknowledgedOccurrences: 5,
        "jcr:created": "2026-08-10T10:00:00.000+00:00",
      },
    })));
    const error = await fetchLoggedError(fetchMock, "abc");

    expect(error.decisions.map(decision => decision.resolution)).toEqual([ "known-issue", "acknowledged" ]);
    expect(error.decisions[0].note).toBe("waiting on the upstream fix");
  });

  it("ignores children that are not decisions", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure,
      somethingElse: { "@name": "somethingElse", "sling:resourceType": "data/Content" },
    })));
    expect((await fetchLoggedError(fetchMock, "abc")).decisions).toEqual([]);
  });

  it("tolerates an error missing the properties its node type says are mandatory", async () => {
    // A model that throws on malformed content would make the screen that exists to show what went
    // wrong the next thing that goes wrong
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      "sling:resourceType": "err/LoggedFailure",
    })));
    const error = await fetchLoggedError(fetchMock, "abc");

    expect(error.name).toBe("abc");
    expect(error.path).toBe("/LoggedErrors/abc");
    expect(error.occurrences).toBe(0);
    expect(error.messages).toEqual([]);
    expect(error.component).toBeUndefined();
  });

  it("tolerates a decision missing what it should carry", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure,
      broken: { "sling:resourceType": "err/Acknowledgement" },
    })));
    const [ decision ] = (await fetchLoggedError(fetchMock, "abc")).decisions;

    expect(decision.name).toBe("");
    expect(decision.resolution).toBe("");
    expect(decision.acknowledgedOccurrences).toBeUndefined();
  });

  it("breaks a tie between decisions taken at the same count by when they were taken", async () => {
    // Two decisions can share an occurrence count — nothing happened in between — and a count
    // cannot order them, so the date does
    const at = (name: string, created: string) => ({
      "@name": name,
      "sling:resourceType": "err/Acknowledgement",
      "resolution": name,
      "acknowledgedOccurrences": 4,
      "jcr:created": created,
    });
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure,
      earlier: at("earlier", "2026-08-02T10:00:00.000+00:00"),
      later: at("later", "2026-08-09T10:00:00.000+00:00"),
    })));
    const decisions = (await fetchLoggedError(fetchMock, "abc")).decisions;

    expect(decisions.map(decision => decision.name)).toEqual([ "later", "earlier" ]);
  });

  it("orders decisions that carry neither a count nor a date without falling over", async () => {
    const bare = (name: string) => ({ "@name": name, "sling:resourceType": "err/Acknowledgement", resolution: "x" });
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, {
      ...failure, one: bare("one"), two: bare("two"),
    })));
    expect((await fetchLoggedError(fetchMock, "abc")).decisions).toHaveLength(2);
  });

  it("fails when the error cannot be read", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(new Response("", { status: 403 })));
    await expect(fetchLoggedError(fetchMock, "abc")).rejects.toThrow("403");
  });

  it("fails rather than inventing an error out of an empty answer", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, null)));
    await expect(fetchLoggedError(fetchMock, "abc")).rejects.toThrow();
  });
});

describe("acknowledgeError", () => {
  it("records a decision", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(
      jsonResponse(200, { status: "ok", acknowledgement: "/LoggedErrors/abc/decision" })));
    expect(await acknowledgeError(fetchMock, "abc", "known-issue")).toEqual({ status: "ok" });
  });

  it("names the selector AND an extension, or the request would not reach the servlet", async () => {
    // `.acknowledge` on its own would leave Sling reading `acknowledge` as the extension, so there
    // would be no selector to match and the default POST servlet would write a `resolution`
    // property onto the error instead of recording a decision
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, { status: "ok" })));
    await acknowledgeError(fetchMock, "abc", "known-issue");

    expect(fetchMock.mock.calls[0][0]).toBe("/LoggedErrors/abc.acknowledge.json");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("POST");
  });

  it("sends the note when there is one, and omits it when there is not", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(200, { status: "ok" })));

    await acknowledgeError(fetchMock, "abc", "wont-fix", "  by design  ");
    expect(fetchMock.mock.calls[0][1]?.body).toBe("resolution=wont-fix&note=by+design");

    await acknowledgeError(fetchMock, "abc", "wont-fix", "   ");
    // A note of nothing but spaces is no note: sending it would store an empty explanation
    expect(fetchMock.mock.calls[1][1]?.body).toBe("resolution=wont-fix");
  });

  it("reports a refused resolution in the server's own words", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(400,
      { status: "error", error: "resolution must name one of the error-triage tags" })));
    const outcome = await acknowledgeError(fetchMock, "abc", "nonsense");

    expect(outcome.status).toBe("invalid");
    expect(outcome.message).toBe("resolution must name one of the error-triage tags");
  });

  it("reports an error that is no longer there", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(404,
      { status: "error", error: "This is not a recorded error" })));
    expect((await acknowledgeError(fetchMock, "gone", "acknowledged")).status).toBe("missing");
  });

  it("reports a server that could not record it", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(jsonResponse(500,
      { status: "error", error: "Could not record the decision" })));
    const outcome = await acknowledgeError(fetchMock, "abc", "acknowledged");

    expect(outcome.status).toBe("failed");
    expect(outcome.message).toBe("Could not record the decision");
  });

  it("explains a refused resolution when the server said nothing readable", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response("<html>", { status: 400 })));
    const outcome = await acknowledgeError(fetchMock, "abc", "nonsense");

    expect(outcome.status).toBe("invalid");
    expect(outcome.message).toBe("That is not a decision this error can carry.");
  });

  it("explains a vanished error when the server said nothing readable", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response("", { status: 404 })));
    const outcome = await acknowledgeError(fetchMock, "gone", "acknowledged");

    expect(outcome.status).toBe("missing");
    expect(outcome.message).toBe("There is no recorded error here any more.");
  });

  it("explains a refusal that came with no readable body", async () => {
    // An unreadable body is never taken as success; only the status decides that
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) => Promise.resolve(new Response("<html>", { status: 500 })));
    const outcome = await acknowledgeError(fetchMock, "abc", "acknowledged");

    expect(outcome.status).toBe("failed");
    expect(outcome.message).toBe("The decision could not be recorded.");
  });
});
