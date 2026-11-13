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

import { act, renderHook, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import type { ActionResponse } from "@iap/deletion/archiveApi";
import { type ArchiveAction, useArchiveAction } from "@iap/deletion/useArchiveAction";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";

const PATH = "/Archive/ab/cd/ef/one";

// The hook, with a recording onDone and onRefused, under the provider that shows its notices
const renderArchiveAction = () => {
  const onDone = vi.fn();
  const onRefused = vi.fn();
  const { result } = renderHook(() => useArchiveAction({ onDone, onRefused }), { wrapper: NoticeProvider });
  return { result, onDone, onRefused };
};

const answering = (response: ActionResponse) => vi.fn<ArchiveAction>(() => Promise.resolve(response));

describe("useArchiveAction", () => {
  it("sends the action for the entry's path", async () => {
    const { result } = renderArchiveAction();
    const action = answering({ status: "restored", restored: [ "/content/one" ] });

    await act(() => result.current.act(PATH, action));

    expect(action).toHaveBeenCalledWith(expect.any(Function), PATH);
  });

  it("reports an entry restored or purged as done", async () => {
    const { result, onDone, onRefused } = renderArchiveAction();

    await act(() => result.current.act(PATH, answering({ status: "deleted" })));

    expect(await screen.findByText(/permanently removed/)).toBeInTheDocument();
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onRefused).not.toHaveBeenCalled();
  });

  it("reports any other answer as refused", async () => {
    const { result, onDone, onRefused } = renderArchiveAction();

    await act(() => result.current.act(PATH, answering({ status: "vetoed", "status.message": "Too recent" })));

    expect(await screen.findByText("Too recent")).toBeInTheDocument();
    expect(onRefused).toHaveBeenCalledTimes(1);
    expect(onDone).not.toHaveBeenCalled();
  });

  it("is busy while a request is out", async () => {
    const { result } = renderArchiveAction();
    let answer!: (response: ActionResponse) => void;
    const action = vi.fn<ArchiveAction>(() => new Promise(resolve => { answer = resolve; }));

    let sent!: Promise<void>;
    act(() => { sent = result.current.act(PATH, action); });
    expect(result.current.busy).toBe(true);

    await act(async () => {
      answer({ status: "deleted" });
      await sent;
    });
    expect(result.current.busy).toBe(false);
  });

  it("offers to send a request that got no answer again, and sends the same one", async () => {
    const { result, onDone } = renderArchiveAction();
    const action = vi.fn<ArchiveAction>()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce({ status: "restored", restored: [ "/content/one" ] });

    await act(() => result.current.act(PATH, action));
    expect(await screen.findByText("The request could not be sent.")).toBeInTheDocument();
    expect(result.current.busy).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => { expect(onDone).toHaveBeenCalled(); });
    expect(action).toHaveBeenCalledTimes(2);
    expect(action.mock.calls[1][1]).toBe(PATH);
  });

  it("offers to send a request the server failed again", async () => {
    const { result, onRefused } = renderArchiveAction();
    const action = answering({ status: "failed", "status.message": "Oak said no" });

    await act(() => result.current.act(PATH, action));
    await userEvent.click(await screen.findByRole("button", { name: "Retry" }));

    await waitFor(() => { expect(action).toHaveBeenCalledTimes(2); });
    expect(onRefused).toHaveBeenCalledTimes(2);
  });
});
