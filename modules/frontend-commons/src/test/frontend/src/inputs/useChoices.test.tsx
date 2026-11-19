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

import { act, renderHook, waitFor } from "@testing-library/react";

import { type ChoiceSource } from "@iap/frontend-commons/inputs/choiceSource";
import useChoices from "@iap/frontend-commons/inputs/useChoices";

const FORMATS = [ { value: "pdf", label: "PDF" } ];

// A source whose search is answered by hand
const pending = (key: string) => {
  const answers: { resolve: (choices: typeof FORMATS) => void; reject: (error: Error) => void }[] = [];
  const source: ChoiceSource = {
    key,
    search: () => new Promise((resolve, reject) => answers.push({ resolve, reject })),
  };
  return { source, answers };
};

describe("useChoices", () => {
  it("is loading until the source answers", async () => {
    const fixed: ChoiceSource = { key: "fixed", search: () => Promise.resolve(FORMATS) };
    const { result } = renderHook(() => useChoices(fixed));

    expect(result.current).toEqual({ choices: [], loading: true, loadError: undefined });
    await waitFor(() => expect(result.current).toEqual({ choices: FORMATS, loading: false, loadError: undefined }));
  });

  it("says why the choices could not be read", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { source, answers } = pending("failing");
    const { result } = renderHook(() => useChoices(source));

    act(() => answers[0].reject(new Error("gone")));

    await waitFor(() => expect(result.current.loadError).toMatch(/gone/));
    expect(result.current.choices).toEqual([]);
    expect(result.current.loading).toBe(false);
  });

  it("reads again for a new source, and keeps only its answer", async () => {
    const first = pending("first");
    const second = pending("second");
    const { result, rerender } = renderHook(({ source }) => useChoices(source), { initialProps: first });

    rerender(second);
    expect(result.current.loading).toBe(true);
    act(() => first.answers[0].resolve([ { value: "old", label: "Old" } ]));
    act(() => second.answers[0].resolve(FORMATS));

    await waitFor(() => expect(result.current.choices).toEqual(FORMATS));
    // A rebuilt source with the same key is not read again
    rerender({ ...second, source: { ...second.source } });
    expect(second.answers).toHaveLength(1);
  });

  it("keeps the new source's choices when the old one answers after it", async () => {
    const first = pending("first");
    const second = pending("second");
    const { result, rerender } = renderHook(({ source }) => useChoices(source), { initialProps: first });

    rerender(second);
    act(() => second.answers[0].resolve(FORMATS));
    await waitFor(() => expect(result.current.choices).toEqual(FORMATS));
    await act(async () => first.answers[0].resolve([ { value: "old", label: "Old" } ]));

    expect(result.current).toEqual({ choices: FORMATS, loading: false, loadError: undefined });
  });
});
