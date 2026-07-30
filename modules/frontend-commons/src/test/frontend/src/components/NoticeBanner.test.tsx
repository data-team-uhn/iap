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
import NoticeBanner from "@iap/frontend-commons/components/NoticeBanner";
import { type Extension } from "@iap/ui-extension/ExtensionList";

const renderBanner = (extension: Extension) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <NoticeBanner extension={extension} />
  </ThemeProvider>
);

describe("NoticeBanner", () => {
  it("renders nothing when the extension carries no message", () => {
    const { container } = renderBanner({ "iap:severity": "warning" });

    expect(container).toBeEmptyDOMElement();
  });

  it("renders the message as markdown", () => {
    renderBanner({ "iap:data": "Maintenance **tonight** — see the [status page](https://status.example.com)" });

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("tonight").tagName).toBe("STRONG");
    expect(screen.getByRole("link", { name: "status page" }))
      .toHaveAttribute("href", "https://status.example.com");
  });

  it("uses the severity registered on the extension", () => {
    renderBanner({ "iap:data": "The service is down", "iap:severity": "error" });

    expect(screen.getByRole("alert")).toHaveClass("MuiAlert-colorError");
  });

  it("defaults to the info severity when none is registered", () => {
    renderBanner({ "iap:data": "Hello" });

    expect(screen.getByRole("alert")).toHaveClass("MuiAlert-colorInfo");
  });

  it("treats an unknown severity as info instead of failing", () => {
    renderBanner({ "iap:data": "Hello", "iap:severity": "catastrophic" });

    expect(screen.getByRole("alert")).toHaveClass("MuiAlert-colorInfo");
  });
});
