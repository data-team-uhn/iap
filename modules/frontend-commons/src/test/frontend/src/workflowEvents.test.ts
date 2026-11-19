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

import { isRefusal } from "@iap/frontend-commons/requestFailure";
import { offers, patch, sendEvent } from "@iap/frontend-commons/workflowEvents";

const failure = (sending: Promise<unknown>) => sending.then(() => undefined, (error: unknown) => error);

// The engine answers in JSON: what an event created, or why it was refused
const answer = (status: number, body: unknown) =>
  vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), { status })));

describe("sendEvent", () => {
  it("posts the event to the node, named by its selector", async () => {
    const doFetch = answer(200, { status: "completed" });

    await expect(sendEvent(doFetch, "/Workflows/review/v2", "retire")).resolves.toBeUndefined();

    expect(doFetch).toHaveBeenCalledWith("/Workflows/review/v2.retire.json",
      expect.objectContaining({ method: "POST" }));
  });

  it("sends a file as the body it was given", async () => {
    const doFetch = answer(200, { status: "completed" });
    const upload = new FormData();
    upload.set("bpmn.xml", new File([ "<definitions/>" ], "bpmn.xml"));

    await sendEvent(doFetch, "/Workflows/review/v2", "save", upload);

    expect(doFetch).toHaveBeenCalledWith("/Workflows/review/v2.save.json", { method: "POST", body: upload });
  });

  it("repeats a parameter given several values", async () => {
    const doFetch = answer(200, { status: "completed" });
    const answers = new URLSearchParams([ [ "days", "Monday" ], [ "days", "Tuesday" ] ]);

    await sendEvent(doFetch, "/Submissions/request", "save", answers);

    const [ , options ] = doFetch.mock.calls[0] as unknown as [ string, { body: URLSearchParams } ];
    expect(options.body.getAll("days")).toEqual([ "Monday", "Tuesday" ]);
  });

  it("resolves with the path of what the event created, as the repository names it", async () => {
    const doFetch = answer(201, { status: "created", path: "/Schemas/study/v1/q/à la carte" });

    await expect(sendEvent(doFetch, "/Schemas/study/v1/q/x", "move")).resolves.toBe("/Schemas/study/v1/q/à la carte");
  });

  it("passes the engine's reason for a refusal on as it stands", async () => {
    const doFetch = answer(409, { status: "error", error: "This request has been submitted and can no longer be changed" });

    await expect(sendEvent(doFetch, "/Submissions/request", "save"))
      .rejects.toThrow("can no longer be changed");
    expect(isRefusal(await failure(sendEvent(doFetch, "/Submissions/request", "save")))).toBe(true);
  });

  it("tells a server error, which may pass, from a refusal", async () => {
    const doFetch = answer(500, { status: "error", error: "Cannot update it" });

    const error = await failure(sendEvent(doFetch, "/Submissions/request", "save"));

    expect(error).toBeInstanceOf(Error);
    expect(isRefusal(error)).toBe(false);
  });

  it("describes a refusal that gives no reason like any failed request", async () => {
    const doFetch = vi.fn(() => Promise.resolve(new Response("Forbidden", { status: 403 })));

    await expect(sendEvent(doFetch, "/Workflows", "create")).rejects.toThrow("You do not have permission");
    // Without a reason, it may be retried
    expect(isRefusal(await failure(sendEvent(doFetch, "/Workflows", "create")))).toBe(false);
  });

  it("describes a request that never completed", async () => {
    const doFetch = vi.fn(() => Promise.reject(new TypeError("Failed to fetch")));

    await expect(sendEvent(doFetch, "/Workflows", "create")).rejects.toThrow("could not be reached");
    expect(isRefusal(await failure(sendEvent(doFetch, "/Workflows", "create")))).toBe(false);
  });
});

describe("offers", () => {
  it("offers what the server listed in @events, and nothing else", () => {
    expect(offers({ "@events": [ "save", "activate" ] }, "activate")).toBe(true);
    expect(offers({ "@events": [ "save" ] }, "activate")).toBe(false);
  });

  it("offers nothing on a node serialized without a list of events", () => {
    expect(offers({}, "save")).toBe(false);
    expect(offers({ "@events": "save" }, "save")).toBe(false);
    expect(offers({ "@events": [ null, 3 ] }, "save")).toBe(false);
  });
});

describe("patch", () => {
  it("sends a patch as one JSON object", () => {
    expect(patch({ title: "New", description: null })).toEqual({ patch: "{\"title\":\"New\",\"description\":null}" });
  });

  it("keeps numbers, truth values and lists as they are", () => {
    expect(JSON.parse(patch({ minAnswers: 1, required: true, tags: [ "a", "b" ] }).patch))
      .toEqual({ minAnswers: 1, required: true, tags: [ "a", "b" ] });
  });
});
