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
import { render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import ParticipatingInstitutions from "@iap/login/ParticipatingInstitutions";

const renderWithTheme = () => render(
  <ThemeProvider theme={appTheme}>
    <ParticipatingInstitutions />
  </ThemeProvider>
);

describe("ParticipatingInstitutions", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders nothing when the registry is not configured", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 404 }));
    renderWithTheme();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByText("Participating institutions")).not.toBeInTheDocument();
  });

  it("renders a logo per institution, falling back to the name without one", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      "jcr:primaryType": "sling:Folder",
      "uhn": {
        "jcr:primaryType": "nt:unstructured",
        "name": "University Health Network",
        "logoLight": "/libs/iap/resources/media/uhn.png",
      },
      "partner": {
        "jcr:primaryType": "nt:unstructured",
        "name": "Partner Institute",
      },
    }), { status: 200 }));
    renderWithTheme();

    expect(await screen.findByText("Participating institutions")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "University Health Network" }))
      .toHaveAttribute("src", "/libs/iap/resources/media/uhn.png");
    expect(screen.getByText("Partner Institute")).toBeInTheDocument();
  });

  it("uses the registry's label property as the heading when present", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      "label": "Réseau participant",
      "one": { "name": "Somewhere" },
    }), { status: 200 }));
    renderWithTheme();

    expect(await screen.findByText("Réseau participant")).toBeInTheDocument();
  });
});
