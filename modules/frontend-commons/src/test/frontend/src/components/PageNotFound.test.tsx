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

/** What the server left on the container, which is all this page knows about the path that was asked for. */
interface Deletion {
  deletedAt?: string;
  deletedBy?: string;
  entryUrl?: string;
}

const renderPageNotFound = (deletion: Deletion = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <PageNotFound {...deletion} />
  </ThemeProvider>
);

/** Configuration reaches the page as `<meta>` tags, the way /libs/iap/conf reaches every other page. */
const configure = (values: Record<string, string>) => {
  Object.entries(values).forEach(([name, content]) => {
    const meta = document.createElement("meta");
    meta.name = name;
    meta.content = content;
    document.head.appendChild(meta);
  });
};

const DELETED_AT = "2026-08-20T14:00:00Z";

describe("PageNotFound", () => {
  afterEach(() => {
    document.head.querySelectorAll("meta").forEach(meta => meta.remove());
  });

  it("explains what went wrong", () => {
    renderPageNotFound();

    expect(screen.getByRole("heading", { level: 1, name: "404" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Not found" })).toBeInTheDocument();
    expect(screen.getByText("The page you are trying to reach does not exist")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });

  it("takes the way back the deployment configured", () => {
    configure({ redirectURL: "/dashboard", redirectLabel: "Back to the dashboard" });

    renderPageNotFound();

    expect(screen.getByRole("button", { name: "Back to the dashboard" })).toBeInTheDocument();
  });

  it.each([
    ["nothing is configured", {}],
    ["what is configured is blank", { redirectURL: "", redirectLabel: "" }],
  ])("offers the homepage when %s", (_case, values) => {
    configure(values);

    renderPageNotFound();

    expect(screen.getByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });

  it("says the page was deleted, and when, instead of that it never existed", () => {
    renderPageNotFound({ deletedAt: DELETED_AT });

    expect(screen.getByRole("heading", { level: 2, name: "Deleted" })).toBeInTheDocument();
    expect(screen.getByText(`This page was deleted on ${new Date(DELETED_AT).toLocaleDateString()}`))
      .toBeInTheDocument();
    // Still a 404, and still offering the way out
    expect(screen.getByRole("heading", { level: 1, name: "404" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Go to the homepage" })).toBeInTheDocument();
  });

  it("says it was deleted even when the date it was given will not parse", () => {
    renderPageNotFound({ deletedAt: "not a date" });

    expect(screen.getByText("This page was deleted")).toBeInTheDocument();
  });

  it("tells a reader who may know who deleted it and where to look", () => {
    renderPageNotFound({ deletedAt: DELETED_AT, deletedBy: "alice", entryUrl: "/admin/archive/abc" });

    expect(screen.getByText("Deleted by alice")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View the archive entry" })).toHaveAttribute(
      "href", "/admin/archive/abc");
    // Supporting detail, not sections of the page: MUI renders subtitle1 as an h6 unless told
    // otherwise, which would put "Deleted by alice" into the document outline
    expect(screen.getAllByRole("heading")).toHaveLength(2);
  });

  it("offers no archive detail to a reader who was not given any", () => {
    renderPageNotFound({ deletedAt: DELETED_AT });

    expect(screen.getByRole("heading", { level: 2, name: "Deleted" })).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(/Deleted by/)).not.toBeInTheDocument();
  });

  it("stays a plain not-found when the path was simply never there", () => {
    renderPageNotFound({});

    expect(screen.getByRole("heading", { level: 2, name: "Not found" })).toBeInTheDocument();
  });
});
