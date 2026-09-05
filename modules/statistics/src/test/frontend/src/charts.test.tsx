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
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import BreakdownChart from "@iap/statistics/BreakdownChart";
import TrendChart from "@iap/statistics/TrendChart";

const themed = (element: React.ReactElement) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">{element}</ThemeProvider>
);

describe("BreakdownChart", () => {
  const slices = [
    { key: "cmorel", value: 9, sampleSize: 19 },
    { key: "achen", value: 4.1, sampleSize: 28 },
    { key: "bpatel", value: 6.8, sampleSize: 36 },
  ];

  it("draws one bar per slice, longest first, with its value beside it", () => {
    themed(<BreakdownChart slices={slices} unit="days" dimension="reviewer" />);

    // Magnitude is the job, so the order is by value and not by however the endpoint sent them
    const labels = screen.getAllByText(/cmorel|achen|bpatel/).map(node => node.textContent);
    expect(labels.slice(0, 3)).toEqual(["cmorel", "bpatel", "achen"]);
    expect(screen.getAllByText("9 days").length).toBeGreaterThan(0);
  });

  it("carries the same numbers as text, so the chart is not the only way to read them", () => {
    themed(<BreakdownChart slices={slices} unit="days" dimension="reviewer" />);

    expect(screen.getByText("By reviewer")).toBeInTheDocument();
    expect(screen.getAllByText("19 requests").length).toBeGreaterThan(0);
  });

  it("names what it is showing, for a reader who cannot see it", () => {
    themed(<BreakdownChart slices={slices} dimension="study type" />);

    expect(screen.getByRole("img", { name: /By study type/ })).toBeInTheDocument();
  });

  it("says so rather than drawing an empty frame when nothing was measurable", () => {
    themed(<BreakdownChart slices={[{ key: "nobody", value: null, sampleSize: 0 }]}
      dimension="reviewer" />);

    expect(screen.getByText("Nothing measurable in any reviewer yet.")).toBeInTheDocument();
  });

  it("tells the reader the sample size a bar rests on when they point at it", async () => {
    themed(<BreakdownChart slices={slices} unit="days" dimension="reviewer" />);

    await userEvent.hover(screen.getAllByText("cmorel")[0]);

    expect(await screen.findByText("9 days over 19 requests")).toBeInTheDocument();
  });
});

describe("TrendChart", () => {
  const series = [
    { key: "2026-06", value: 40, sampleSize: 12 },
    { key: "2026-07", value: 30, sampleSize: 15 },
    { key: "2026-08", value: 21, sampleSize: 9 },
  ];

  it("labels only the end of the line, and both ends of the axis", () => {
    themed(<TrendChart series={series} unit="days" />);

    // A number on every point is chaos and goes unread; the endpoint is the one worth labelling.
    // Scoped to the plot, because the table beside it deliberately carries every value.
    const plot = within(screen.getByRole("img", { name: /Month by month/ }));
    expect(plot.getAllByText("21 days")).toHaveLength(1);
    expect(plot.queryByText("30 days")).not.toBeInTheDocument();
    expect(plot.getByText(/Jun/)).toBeInTheDocument();
    expect(plot.getByText(/Aug/)).toBeInTheDocument();
    expect(plot.queryByText(/Jul/)).not.toBeInTheDocument();
  });

  it("says which month a figure belongs to, since a month keeps filling up", () => {
    themed(<TrendChart series={series} unit="days" />);

    expect(screen.getByText(/the most recent months are still filling up/)).toBeInTheDocument();
  });

  it("carries the months as text too", () => {
    themed(<TrendChart series={series} unit="days" />);

    expect(screen.getByText("Month by month")).toBeInTheDocument();
    expect(screen.getAllByText("12 requests").length).toBeGreaterThan(0);
  });

  it("refuses to draw a trend from one point", () => {
    themed(<TrendChart series={[{ key: "2026-08", value: 21, sampleSize: 9 }]} />);

    expect(screen.getByText("Not enough months yet to show a trend.")).toBeInTheDocument();
  });

  it("leaves out the months that measured nothing", () => {
    themed(<TrendChart series={[...series, { key: "2026-09", value: null, sampleSize: 0 }]}
      unit="days" />);

    // The unmeasured month is not plotted, so August is still the end of the line
    const plot = within(screen.getByRole("img", { name: /Month by month/ }));
    expect(plot.getAllByText("21 days")).toHaveLength(1);
    expect(plot.queryByText(/Sep/)).not.toBeInTheDocument();
  });
});
