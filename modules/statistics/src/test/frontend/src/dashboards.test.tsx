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

import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import MetricsDashboard from "@iap/statistics/MetricsDashboard";
import MetricsWidget from "@iap/statistics/MetricsWidget";
import MetricTile from "@iap/statistics/MetricTile";

const metric = (over: Record<string, unknown> = {}) => ({
  name: "timeToAuth", label: "Time to authorization", category: "Turnaround", unit: "days",
  value: 32.5, sampleSize: 120, breakdown: [], series: [], ...over,
});

const answering = (metrics: unknown[]) =>
  vi.fn(() => Promise.resolve(new Response(JSON.stringify({ metrics }),
    { status: 200, headers: { "Content-Type": "application/json" } })));

const themed = (element: React.ReactElement) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter>{element}</MemoryRouter>
  </ThemeProvider>
);

afterEach(() => {
  vi.restoreAllMocks();
});

describe("MetricTile", () => {
  it("shows the number with the sample it rests on", () => {
    themed(<MetricTile metric={metric()} />);

    expect(screen.getByText("32.5 days")).toBeInTheDocument();
    expect(screen.getByText("120 requests")).toBeInTheDocument();
  });

  // A reader who cannot see the sample size is being invited to over-read the number
  it("says nothing was measured rather than showing a sample of nothing", () => {
    themed(<MetricTile metric={metric({ value: null, sampleSize: 0 })} />);

    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("Nothing measured yet")).toBeInTheDocument();
  });
});

describe("MetricsWidget", () => {
  it("leads with the first few metrics and offers the way through to the rest", async () => {
    vi.stubGlobal("fetch", answering([
      metric(), metric({ name: "b", label: "B" }), metric({ name: "c", label: "C" }),
      metric({ name: "d", label: "D" }),
    ]));
    themed(<MetricsWidget />);

    expect(await screen.findByText("Time to authorization")).toBeInTheDocument();
    expect(screen.getByText("C")).toBeInTheDocument();
    // The fourth is one too many for a dashboard frame; the link is how it is reached
    expect(screen.queryByText("D")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All metrics and trends" }))
      .toHaveAttribute("href", "/Statistics");
  });

  it("says when there is nothing defined, rather than showing an empty frame", async () => {
    vi.stubGlobal("fetch", answering([]));
    themed(<MetricsWidget />);

    expect(await screen.findByText("No metrics are defined yet.")).toBeInTheDocument();
  });

  // "No metrics" and "the metrics could not be read" look identical otherwise, and only one of them
  // is somebody's problem
  it("says when the metrics could not be read at all", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("", { status: 500 }))));
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    themed(<MetricsWidget />);

    expect(await screen.findByText("The metrics could not be read.")).toBeInTheDocument();
  });
});

describe("MetricsDashboard", () => {
  it("groups the metrics by category and draws what each one has", async () => {
    vi.stubGlobal("fetch", answering([
      metric({
        series: [
          { key: "2026-06", value: 40, sampleSize: 12 },
          { key: "2026-07", value: 30, sampleSize: 15 },
        ],
        breakdown: [{ key: "achen", value: 30, sampleSize: 80 }],
      }),
      metric({ name: "issues", label: "Issues", category: "Quality", unit: "issues", value: 1.2 }),
    ]));
    themed(<MetricsDashboard />);

    expect(await screen.findByText("Turnaround")).toBeInTheDocument();
    expect(screen.getByText("Quality")).toBeInTheDocument();
    // Twice each: the heading a reader sees, and the caption of the table beside it
    expect(screen.getAllByText("Month by month")).toHaveLength(1);
    expect(screen.getAllByText("By reviewer")).toHaveLength(2);
  });

  it("calls a split by schema what it is, and drops the path from the axis", async () => {
    vi.stubGlobal("fetch", answering([metric({
      breakdown: [{ key: "/Schemas/dataStudy", value: 46.6, sampleSize: 103 }],
    })]));
    themed(<MetricsDashboard />);

    expect(await screen.findAllByText("By study type")).toHaveLength(2);
    expect(screen.getAllByText("dataStudy").length).toBeGreaterThan(0);
    expect(screen.queryByText("/Schemas/dataStudy")).not.toBeInTheDocument();
  });

  it("shows a metric with no charts as its figure alone", async () => {
    vi.stubGlobal("fetch", answering([metric({ description: "How long it takes" })]));
    themed(<MetricsDashboard />);

    expect(await screen.findByText("How long it takes")).toBeInTheDocument();
    expect(screen.queryByText("Month by month")).not.toBeInTheDocument();
  });

  it("says when there is nothing to show, and when it could not be read", async () => {
    vi.stubGlobal("fetch", answering([]));
    const { unmount } = themed(<MetricsDashboard />);
    expect(await screen.findByText("No metrics are defined yet.")).toBeInTheDocument();
    unmount();

    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("", { status: 503 }))));
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    themed(<MetricsDashboard />);
    expect(await screen.findByText("The metrics could not be read.")).toBeInTheDocument();
  });
});
