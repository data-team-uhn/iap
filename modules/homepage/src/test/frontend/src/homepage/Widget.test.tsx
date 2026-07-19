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

import Widget from "@iap/homepage/homepage/Widget";

describe("Widget", () => {
  it("renders its children on a Paper surface", () => {
    render(<Widget><div>widget body</div></Widget>);

    const body = screen.getByText("widget body");
    expect(body).toBeInTheDocument();
    expect(body.closest(".MuiPaper-root")).not.toBeNull();
  });

  it("renders the title as a heading when provided", () => {
    render(<Widget title="My widget"><div>body</div></Widget>);

    expect(screen.getByRole("heading", { name: "My widget" })).toBeInTheDocument();
  });

  it("renders no heading when no title is given", () => {
    render(<Widget><div>body</div></Widget>);

    expect(screen.queryByRole("heading")).toBeNull();
  });

  it("renders the subtitle when provided", () => {
    render(<Widget title="My widget" subtitle="a short description"><div>body</div></Widget>);

    expect(screen.getByText("a short description")).toBeInTheDocument();
  });

  it("still renders its content on a Paper surface when emphasised", () => {
    // The tint itself is theme-driven and only visible in a browser; here we just confirm the
    // emphasis prop is accepted and doesn't disturb rendering.
    render(<Widget emphasis title="Highlighted"><div>body</div></Widget>);

    expect(screen.getByText("body").closest(".MuiPaper-root")).not.toBeNull();
    expect(screen.getByRole("heading", { name: "Highlighted" })).toBeInTheDocument();
  });

  it("does not render the header when hideHeader is set", () => {
    render(<Widget title="My widget" subtitle="a description" hideHeader><div>body</div></Widget>);

    expect(screen.queryByRole("heading")).toBeNull();
    expect(screen.queryByText("a description")).toBeNull();
    // the content itself is still rendered
    expect(screen.getByText("body")).toBeInTheDocument();
  });
});
