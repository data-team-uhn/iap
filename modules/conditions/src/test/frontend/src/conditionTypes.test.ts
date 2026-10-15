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

import { renderHook, waitFor } from "@testing-library/react";

import { loadConditionTypes, useConditionTypes } from "@iap/conditions/conditionTypes";
import { catalogueFetch } from "@iap/conditions/conditionTypes.fixture";

const QUESTIONS = [
  { value: "u-what", label: "What do you need?", type: "text", multiple: false,
    choices: [ { value: "laptop", label: "Laptop" } ] },
  { value: "u-many", label: "How many?", type: "long", multiple: false },
];

const load = (candidates: Record<string, unknown[]> = { "/Schemas/loan/v1/why": QUESTIONS }) =>
  loadConditionTypes(vi.fn(catalogueFetch(candidates)), "/Schemas/loan/v1/why");

describe("conditionTypes", () => {
  it("takes the comparators and aggregates from the catalogue", async () => {
    const types = await load();
    expect(types?.comparators.map(comparator => comparator.name)).toContain("greater or equal");
    expect(types?.aggregates.map(aggregate => aggregate.name)).toEqual([ "count", "sum" ]);
  });

  it("offers every source but the literal one, in their order", async () => {
    const types = await load();
    expect(types?.sources.map(source => source.name)).toEqual([ "answer", "tags", "property", "ownProperty" ]);
    expect(types?.sources.find(source => source.name === "ownProperty")?.offered).toBe(false);
  });

  it("lists what a source names here, and what an operand naming it holds and reads as", async () => {
    const answer = (await load())?.sources[0];
    expect(answer?.candidates).toEqual([
      { value: "u-what", label: "What do you need?" }, { value: "u-many", label: "How many?" } ]);
    expect(answer?.shape([ "u-many" ])).toEqual({ type: "long", multiple: false, choices: undefined });
    expect(answer?.describe([ "u-what" ])).toBe("the answer to “What do you need?”");
    // Something it cannot list reads as it is named
    expect(answer?.describe([ "form/gone" ])).toBe("the answer to “form/gone”");
    expect(answer?.describe([])).toBe("the answer to “Question”");
  });

  it("gives a source naming nothing its own shape, with the choices of the catalogue it names", async () => {
    const tags = (await load())?.sources[1];
    expect(tags?.candidates).toBeUndefined();
    expect(tags?.shape([])).toEqual({ type: "text", multiple: true, choices: [
      { value: "draft", label: "Draft" }, { value: "urgent", label: "Urgent" }, { value: "plain", label: "plain" } ] });
    expect(tags?.describe([])).toBe("the tag list");
    const property = (await load())?.sources[2];
    expect(property?.shape([ "status" ])).toEqual({});
    expect(property?.describe([ "status" ])).toBe("the status");
  });

  it("lists nothing a source names when it cannot be read", async () => {
    const answer = (await load({}))?.sources[0];
    expect(answer?.candidates).toEqual([]);
    expect(answer?.shape([ "u-what" ])).toEqual({});
  });

  it("gives nothing when the catalogue cannot be read", async () => {
    expect(await loadConditionTypes(vi.fn(() => Promise.resolve({ ok: false } as Response)), "/x")).toBeUndefined();
    expect(await loadConditionTypes(vi.fn(() => Promise.reject(new Error("offline"))), "/x")).toBeUndefined();
  });

  it("treats a category the catalogue leaves out as empty", async () => {
    const types = await loadConditionTypes(vi.fn(() => Promise.resolve({ ok: true,
      json: () => Promise.resolve({ items: {} }) } as unknown as Response)), "/x");
    expect(types).toEqual({ comparators: [], aggregates: [], sources: [] });
  });

  it("drops what it read once the hook is gone", async () => {
    const fetching = vi.fn(catalogueFetch());
    vi.stubGlobal("fetch", fetching);
    const { result, unmount } = renderHook(() => useConditionTypes("/Schemas/loan/v1"));
    unmount();
    await waitFor(() => expect(fetching).toHaveBeenCalled());
    expect(result.current).toBeUndefined();
    vi.unstubAllGlobals();
  });

  it("reads them once for the content a hook is given", async () => {
    vi.stubGlobal("fetch", vi.fn(catalogueFetch({ "/Schemas/loan/v1": QUESTIONS })));
    const { result, unmount } = renderHook(() => useConditionTypes("/Schemas/loan/v1"));
    expect(result.current).toBeUndefined();
    await waitFor(() => expect(result.current?.sources[0].candidates).toHaveLength(2));
    unmount();
    vi.unstubAllGlobals();
  });
});
