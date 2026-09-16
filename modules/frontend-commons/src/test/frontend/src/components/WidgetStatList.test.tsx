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

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import WidgetStatList, { type WidgetStat } from "@iap/frontend-commons/components/WidgetStatList";

// A linked label is a router link, so the list needs a router around it whenever one is given.
const list = (stats: WidgetStat[]) => render(<MemoryRouter><WidgetStatList stats={stats} /></MemoryRouter>);

describe("WidgetStatList", () => {
  it("shows each stat's value beside its label", () => {
    list([
      { label: "Archived in the last 24 hours", value: 3 },
      { label: "Archived in total", value: 218 },
    ]);

    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("Archived in the last 24 hours")).toBeInTheDocument();
    expect(screen.getByText("218")).toBeInTheDocument();
    expect(screen.getByText("Archived in total")).toBeInTheDocument();
  });

  it("marks an approximate count as a lower bound", () => {
    list([{ label: "Recorded in total", value: 10000, approximate: true }]);

    expect(screen.getByText("10000+")).toBeInTheDocument();
  });

  it("leaves the number open for a count that could not be read, rather than claiming none", () => {
    // A count of none and a count nobody could take are opposite answers, and a widget that showed
    // "0" for the second would be making a claim the server never made
    list([{ label: "Workflows", unknownTitle: "The workflows here could not be counted" }]);

    const value = screen.getByText("?");
    expect(value).toBeInTheDocument();
    expect(value).toHaveAttribute("title", "The workflows here could not be counted");
  });

  it("still says a count could not be read when given no wording of its own", () => {
    list([{ label: "Workflows" }]);

    expect(screen.getByText("?")).toHaveAttribute("title", "This could not be counted");
  });

  it("colours an emphasised count as a problem only while something is outstanding", () => {
    // A widget that is permanently red stops being read, so the colour has to mean something. The
    // theme's colours are not resolved under jsdom, so this compares how the three cases are
    // styled rather than what they come out as: emphasised-and-outstanding has to differ from both
    // an emphasised zero and an ordinary count, and those two have to agree with each other.
    const outstanding = list([{ label: "Needing attention", value: 3, emphasis: true }]);
    const emphasised = screen.getByText("3").className;
    outstanding.unmount();

    const nothingOutstanding = list([{ label: "Needing attention", value: 0, emphasis: true }]);
    const emphasisedZero = screen.getByText("0").className;
    nothingOutstanding.unmount();

    list([{ label: "Needing attention", value: 0 }]);
    expect(emphasised).not.toBe(emphasisedZero);
    expect(emphasisedZero).toBe(screen.getByText("0").className);
  });

  it("renders a label with a link as a link to its destination", () => {
    list([{ label: "Needing attention", value: 3, href: "/admin/errors" }]);

    expect(screen.getByRole("link", { name: "Needing attention" })).toHaveAttribute("href", "/admin/errors");
  });

  it("renders a label without a link as plain text", () => {
    list([{ label: "Archived in total", value: 218 }]);

    expect(screen.getByText("Archived in total")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("shows a boolean stat as the state it is in", () => {
    list([{ label: "Catching mail", mode: "boolean", value: true }]);

    expect(screen.getByText("On")).toBeInTheDocument();
  });

  it("shows a boolean stat that is off as off", () => {
    list([{ label: "Catching mail", mode: "boolean", value: false }]);

    expect(screen.getByText("Off")).toBeInTheDocument();
  });

  it("calls the two states whatever the widget calls them", () => {
    const enabled = list([
      { label: "Nightly export", mode: "boolean", value: true, trueLabel: "Enabled", falseLabel: "Disabled" },
    ]);
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    enabled.unmount();

    list([{ label: "Nightly export", mode: "boolean", value: false, trueLabel: "Enabled", falseLabel: "Disabled" }]);
    expect(screen.getByText("Disabled")).toBeInTheDocument();
  });

  it("lays every stat out in one grid, so labels line up however wide the values are", () => {
    // The point of the whole component: a four-digit count next to a one-digit one must not leave
    // the two labels starting in different places
    const { container } = list([
      { label: "Needing attention", value: 3 },
      { label: "Recorded in total", value: 4123 },
      { label: "Catching mail", mode: "boolean", value: true },
    ]);

    const grid = container.querySelector("div");
    expect(grid).not.toBeNull();
    // Each stat contributes its value and its label to the same grid, rather than each row being
    // laid out on its own
    expect(grid?.children).toHaveLength(6);
    expect(getComputedStyle(grid as Element).display).toBe("grid");
  });

  it("renders nothing for an empty list", () => {
    const { container } = list([]);

    expect(container.querySelector("div")?.children).toHaveLength(0);
  });
});
