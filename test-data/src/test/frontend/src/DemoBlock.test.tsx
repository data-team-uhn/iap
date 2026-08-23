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

import { appTheme } from "@iap/frontend-commons/appTheme";
import DemoBlock from "@iap/test-data/DemoBlock";

const renderBlock = (extension: Record<string, unknown>) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <DemoBlock extension={extension} />
  </ThemeProvider>
);

describe("DemoBlock", () => {
  it("labels itself with the name of the extension that registered it", () => {
    renderBlock({ "ext:name": "Sidebar demo" });

    expect(screen.getByText("Sidebar demo")).toBeInTheDocument();
  });

  it("falls back to a generic label for an unnamed extension", () => {
    renderBlock({});

    expect(screen.getByText("Demo block")).toBeInTheDocument();
  });

  it("pads itself with the requested number of filler lines", () => {
    renderBlock({ "ext:name": "Tall demo", "ext:data": 3 });

    expect(screen.getByText("Filler line 1")).toBeInTheDocument();
    expect(screen.getByText("Filler line 3")).toBeInTheDocument();
    expect(screen.queryByText("Filler line 4")).not.toBeInTheDocument();
  });

  it("reads a filler count that arrived as a string", () => {
    renderBlock({ "ext:data": "2" });

    expect(screen.getByText("Filler line 2")).toBeInTheDocument();
    expect(screen.queryByText("Filler line 3")).not.toBeInTheDocument();
  });

  it.each([undefined, "not a number", 0])("renders no filler for %o", data => {
    renderBlock({ "ext:data": data });

    expect(screen.queryByText(/Filler line/)).not.toBeInTheDocument();
  });
});
