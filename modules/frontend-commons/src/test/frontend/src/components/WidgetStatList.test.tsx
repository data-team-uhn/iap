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
import { MemoryRouter } from "react-router";

import WidgetStatList from "@iap/frontend-commons/components/WidgetStatList";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

/** Answers every fetch with one summary. */
const answering = (body: unknown, status = 200) =>
  vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(status, body));

// A linked label is a router link, so the list needs a router around it whenever one is given.
const list = (body: unknown, props: { name?: string; hrefFor?: (path: string) => string } = {}) => {
  answering(body);
  return render(
    <MemoryRouter>
      <WidgetStatList url="/Archive" name={props.name ?? "archive"} hrefFor={props.hrefFor} />
    </MemoryRouter>
  );
};

describe("WidgetStatList", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows each figure's value beside its label", async () => {
    list({
      last24Hours: { label: "Archived in the last 24 hours", value: 3 },
      total: { label: "Archived in total", value: 218 },
    });

    expect(await screen.findByText("3")).toBeInTheDocument();
    expect(screen.getByText("Archived in the last 24 hours")).toBeInTheDocument();
    expect(screen.getByText("218")).toBeInTheDocument();
    expect(screen.getByText("Archived in total")).toBeInTheDocument();
  });

  it("asks the resource for its summary", async () => {
    const fetchMock = answering({ total: { label: "Archived in total", value: 0 } });
    render(<MemoryRouter><WidgetStatList url="/Archive" name="archive" /></MemoryRouter>);

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    expect(fetchMock.mock.calls[0][0]).toBe("/Archive.adminSummary.json");
  });

  it("lists the figures in the order the summary gave them", async () => {
    const { container } = list({
      needingAttention: { label: "Needing attention", value: 3 },
      total: { label: "Recorded in total", value: 41 },
    });

    await screen.findByText("Needing attention");
    const labels = [ ...container.querySelectorAll("dt") ].map(term => term.textContent);
    expect(labels).toEqual([ "Needing attention", "Recorded in total" ]);
  });

  it("marks an approximate count as a lower bound", async () => {
    list({ total: { label: "Recorded in total", value: 10000, approximate: true } });

    expect(await screen.findByText("10000+")).toBeInTheDocument();
  });

  it("keeps a count that could not be taken, with its value unknown", async () => {
    const failed = list({ "/SystemWorkflows": { label: "System workflows", value: null, important: "zero" } });

    const unknown = await screen.findByText("?");
    expect(unknown).toHaveAttribute("title", "Count unknown");
    expect(screen.getByText("System workflows")).toBeInTheDocument();
    const unknownClass = unknown.className;
    failed.unmount();

    // An unknown count is not zero, so it is not coloured as one
    list({ "/SystemWorkflows": { label: "System workflows", value: 0 } });
    expect(unknownClass).toBe((await screen.findByText("0")).className);
  });

  it("shows a state rather than a figure as the state it is in", async () => {
    list({ enabled: { label: "Catching mail", value: true } });

    expect(await screen.findByText("On")).toBeInTheDocument();
  });

  it("shows a state that is off as off", async () => {
    list({ enabled: { label: "Catching mail", value: false } });

    expect(await screen.findByText("Off")).toBeInTheDocument();
  });

  it("colours a count marked on non-zero as a problem only while something is outstanding", async () => {
    // jsdom does not resolve the theme's colours, so the three cases are compared against each
    // other rather than against a colour. Marked-and-outstanding differs from both a marked zero
    // and an ordinary count; those two agree.
    const outstanding = list({ needingAttention: { label: "Needing attention", value: 3, important: "nonzero" } });
    const marked = (await screen.findByText("3")).className;
    outstanding.unmount();

    const none = list({ needingAttention: { label: "Needing attention", value: 0, important: "nonzero" } });
    const markedZero = (await screen.findByText("0")).className;
    none.unmount();

    list({ needingAttention: { label: "Needing attention", value: 0 } });
    expect(marked).not.toBe(markedZero);
    expect(markedZero).toBe((await screen.findByText("0")).className);
  });

  it("colours a count marked on zero as a problem only while it is at zero", async () => {
    // The opposite marking, for a figure whose absence is what warrants acting
    const none = list({ reviewers: { label: "Reviewers available", value: 0, important: "zero" } });
    const markedZero = (await screen.findByText("0")).className;
    none.unmount();

    const some = list({ reviewers: { label: "Reviewers available", value: 2, important: "zero" } });
    const markedSome = (await screen.findByText("2")).className;
    some.unmount();

    list({ reviewers: { label: "Reviewers available", value: 2 } });
    expect(markedZero).not.toBe(markedSome);
    expect(markedSome).toBe((await screen.findByText("2")).className);
  });

  it("links a label to where its figure leads", async () => {
    list(
      { "/Workflows": { label: "Workflows", value: 3, path: "/Workflows" } },
      { name: "workflows", hrefFor: path => `/admin/workflows${path}` }
    );

    expect(await screen.findByRole("link", { name: "Workflows" }))
      .toHaveAttribute("href", "/admin/workflows/Workflows");
  });

  it("keeps the label a term of its own when it links somewhere", async () => {
    // The link sits inside the term, so a linked figure is found the same way as a plain one
    list(
      { "/Workflows": { label: "Workflows", value: 3, path: "/Workflows" } },
      { name: "workflows", hrefFor: path => path }
    );

    const link = await screen.findByRole("link", { name: "Workflows" });
    expect(link.closest("dt")).not.toBeNull();
    expect(link.closest("dt")?.nextElementSibling?.tagName).toBe("DD");
  });

  it("leaves a label plain when the figure leads nowhere", async () => {
    list({ total: { label: "Archived in total", value: 218, path: "/Archive" } });

    expect(await screen.findByText("Archived in total")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("leaves a label plain when the figure has no path of its own", async () => {
    list({ total: { label: "Archived in total", value: 218 } }, { hrefFor: path => path });

    expect(await screen.findByText("Archived in total")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("describes each value by the label it belongs to", async () => {
    list({
      total: { label: "Archived in total", value: 218 },
      enabled: { label: "Catching mail", value: true },
    });

    const term = await screen.findByText("Archived in total");
    expect(term.tagName).toBe("DT");
    expect(term.nextElementSibling?.tagName).toBe("DD");
    expect(term.nextElementSibling).toHaveTextContent("218");
  });

  it("lays every figure out in one grid, so labels line up however wide the values are", async () => {
    // The point of the whole component: a four-digit count next to a one-digit one must not leave
    // the two labels starting in different places
    const { container } = list({
      needingAttention: { label: "Needing attention", value: 3 },
      total: { label: "Recorded in total", value: 4123 },
      enabled: { label: "Catching mail", value: true },
    });

    await screen.findByText("Needing attention");
    const grid = container.querySelector("dl");
    expect(grid?.children).toHaveLength(6);
    expect(getComputedStyle(grid as Element).display).toBe("grid");
  });

  it("waits with a placeholder rather than an empty list", () => {
    answering({});
    render(<MemoryRouter><WidgetStatList url="/LoggedErrors" name="recorded errors" /></MemoryRouter>);

    expect(screen.getByLabelText("Loading the recorded errors summary")).toBeInTheDocument();
  });

  it("says the summary is unavailable rather than showing nothing", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 404 }));
    render(<MemoryRouter><WidgetStatList url="/Archive" name="archive" /></MemoryRouter>);

    expect(await screen.findByText("The archive summary is not available to you.")).toBeInTheDocument();
  });

  it("says the same when the answer cannot be read at all", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("not json", { status: 200 }));
    render(<MemoryRouter><WidgetStatList url="/Archive" name="archive" /></MemoryRouter>);

    expect(await screen.findByText("The archive summary is not available to you.")).toBeInTheDocument();
  });

  it("leaves out an entry that is not a figure rather than showing a gap", async () => {
    const { container } = list({
      total: { label: "Archived in total", value: 218 },
      broken: { label: "Missing its value" },
      alsoBroken: "not an entry at all",
    });

    expect(await screen.findByText("218")).toBeInTheDocument();
    expect(screen.queryByText("Missing its value")).not.toBeInTheDocument();
    expect(container.querySelectorAll("dt")).toHaveLength(1);
  });

  it("renders nothing for a summary with no figures", async () => {
    const { container } = list({});

    await waitFor(() => { expect(container.querySelector("dl")).not.toBeNull(); });
    expect(container.querySelector("dl")?.children).toHaveLength(0);
  });

  it("renders nothing for an answer that is not a summary", async () => {
    const { container } = list(null);

    await waitFor(() => { expect(container.querySelector("dl")).not.toBeNull(); });
    expect(container.querySelector("dl")?.children).toHaveLength(0);
  });
});
