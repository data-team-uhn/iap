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
import PageNotFound from "@iap/frontend-commons/components/PageNotFound";

const renderPageNotFound = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <PageNotFound />
  </ThemeProvider>
);

describe("PageNotFound", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("explains what went wrong", async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404 });

    renderPageNotFound();

    expect(screen.getByRole("heading", { level: 1, name: "404" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Not found" })).toBeInTheDocument();
    expect(screen.getByText("The page you are trying to reach does not exist")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });

  it("takes the redirect the server suggests", async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ RedirectURL: "/dashboard", RedirectLabel: "Back to the dashboard" }),
    });

    renderPageNotFound();

    expect(await screen.findByRole("button", { name: "Back to the dashboard" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith("/RedirectURL.json");
  });

  it("keeps the default redirect when the server has no suggestion", async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 });

    renderPageNotFound();

    expect(await screen.findByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });

  it("keeps the default redirect when the lookup fails outright", async () => {
    fetchMock.mockRejectedValue(new Error("offline"));

    renderPageNotFound();

    expect(await screen.findByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });
});
