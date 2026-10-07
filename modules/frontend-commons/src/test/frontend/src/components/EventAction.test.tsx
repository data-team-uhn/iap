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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { ActionIcon, EventAction } from "@iap/frontend-commons/components/EventAction";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";

afterEach(() => vi.unstubAllGlobals());

// Answers every event as the engine would: completed, or refused with its reason
const serve = (refusal?: string) => {
  const fetch = vi.fn((url: string) => Promise.resolve({
    ok: !refusal, status: refusal ? 409 : 200, url, redirected: false,
    json: () => Promise.resolve(refusal ? { error: refusal } : { status: "completed" }),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetch);
  return fetch;
};

const renderAction = () => {
  const reload = vi.fn();
  render(
    <EventAction
      path="/Workflows/review"
      reload={reload}
      icon={<span />}
      label="Retire"
      event="retire"
      title="Retire Review"
      explanation="No new reviews will start."
      done="Review is retired"
    />,
    { wrapper: NoticeProvider },
  );
  return reload;
};

const openConfirmation = async () => {
  fireEvent.click(screen.getByRole("button", { name: "Retire" }));
  return screen.findByRole("dialog");
};

describe("EventAction", () => {
  it("asks before it sends anything", async () => {
    const fetch = serve();
    renderAction();

    const dialog = await openConfirmation();

    expect(within(dialog).getByRole("heading", { name: "Retire Review" })).toBeInTheDocument();
    expect(within(dialog).getByText("No new reviews will start.")).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("sends the event once confirmed, then says so and reloads", async () => {
    const fetch = serve();
    const reload = renderAction();

    fireEvent.click(within(await openConfirmation()).getByRole("button", { name: "Retire" }));

    await waitFor(() => { expect(reload).toHaveBeenCalled(); });
    expect(fetch).toHaveBeenCalledWith("/Workflows/review.retire.json", expect.objectContaining({ method: "POST" }));
    const notice = await screen.findByRole("alert");
    expect(notice).toHaveTextContent("Review is retired");
    expect(notice).toHaveClass("MuiAlert-colorSuccess");
    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
  });

  it("shows the engine's refusal and leaves the page as it was", async () => {
    serve("Not while reviews are open");
    const reload = renderAction();

    const dialog = await openConfirmation();
    fireEvent.click(within(dialog).getByRole("button", { name: "Retire" }));

    expect(await within(dialog).findByText("Not while reviews are open")).toBeInTheDocument();
    expect(screen.queryByText("Review is retired")).not.toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it("sends nothing when the confirmation is cancelled", async () => {
    const fetch = serve();
    renderAction();

    fireEvent.click(within(await openConfirmation()).getByRole("button", { name: "Cancel" }));

    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("ActionIcon", () => {
  it("is a button named by its tooltip", async () => {
    const onClick = vi.fn();
    render(<ActionIcon label="Rename" icon={<span />} onClick={onClick} />);
    const button = screen.getByRole("button", { name: "Rename" });

    fireEvent.mouseOver(button);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("Rename");

    fireEvent.click(button);
    expect(onClick).toHaveBeenCalled();
  });

  it("is a link when the action only goes somewhere", () => {
    render(<MemoryRouter><ActionIcon label="Edit" icon={<span />} to="/admin/workflows/review/v2" /></MemoryRouter>);

    expect(screen.getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/admin/workflows/review/v2");
  });
});
