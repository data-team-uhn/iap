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
    // A count of none and a count nobody could take are opposite answers; "0" for the second claims
    // something the server never said
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
    // jsdom does not resolve the theme's colours, so the three cases are compared against each
    // other rather than against a colour. Emphasised-and-outstanding differs from both an
    // emphasised zero and an ordinary count; those two agree.
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

    const grid = container.querySelector("dl");
    expect(grid).not.toBeNull();
    // Each stat contributes its value and its label to the same grid, rather than each row being
    // laid out on its own
    expect(grid?.children).toHaveLength(6);
    expect(getComputedStyle(grid as Element).display).toBe("grid");
  });

  it("describes each value by the label it belongs to", () => {
    // Written label first, displayed value first: the reading order is what a screen reader follows,
    // and it is the opposite of the visual one
    list([
      { label: "Archived in total", value: 218 },
      { label: "Catching mail", mode: "boolean", value: true },
    ]);

    const term = screen.getByText("Archived in total");
    expect(term.tagName).toBe("DT");
    expect(term.nextElementSibling?.tagName).toBe("DD");
    expect(term.nextElementSibling).toHaveTextContent("218");

    const state = screen.getByText("Catching mail");
    expect(state.tagName).toBe("DT");
    expect(state.nextElementSibling?.tagName).toBe("DD");
    expect(state.nextElementSibling).toHaveTextContent("On");
  });

  it("keeps the label a term of its own when it links somewhere", () => {
    // The link goes inside the term rather than replacing it, so a linked stat is still found the
    // same way as a plain one
    list([{ label: "Needing attention", value: 3, href: "/admin/errors" }]);

    const link = screen.getByRole("link", { name: "Needing attention" });
    expect(link.closest("dt")).not.toBeNull();
    expect(link.closest("dt")?.nextElementSibling?.tagName).toBe("DD");
  });

  it("renders nothing for an empty list", () => {
    const { container } = list([]);

    expect(container.querySelector("dl")?.children).toHaveLength(0);
  });
});
