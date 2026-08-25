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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import LlmConfigWidget from "@iap/llm/LlmConfigWidget";

const catalogJson = {
  activeProvider: "local",
  activeModel: "llama3.2-3b",
  providers: [
    {
      name: "local",
      label: "Local (Ollama)",
      endpoint: "http://localhost:11434/v1",
      models: [ { name: "llama3.2-3b" }, { name: "other-model" } ],
    },
    { name: "prompter", models: [ { name: "GPT-OSS-120B" } ] },
  ],
};

// The widget reads the catalog through useAuthenticatedFetch, so every answer carries the `url` it
// came back from: that is how the login page an expired session is redirected to is recognised.
const stubFetch = (body: unknown = catalogJson) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
    ok: true, status: 200, statusText: "OK", url,
    json: () => Promise.resolve(body),
  } as unknown as Response)));

const stubFailingFetch = (status: number, statusText: string) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText, url }) as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("LlmConfigWidget", () => {
  it("summarizes which LLM is in use", async () => {
    stubFetch();
    render(<LlmConfigWidget />);

    expect(await screen.findByText("Local (Ollama)")).toBeInTheDocument();
    expect(screen.getByText("llama3.2-3b")).toBeInTheDocument();
    expect(screen.getByText("2 providers, 3 models")).toBeInTheDocument();
  });

  it("says so when nothing is selected", async () => {
    stubFetch({ providers: [ { name: "local", models: [] } ] });
    render(<LlmConfigWidget />);

    expect(await screen.findByText("No LLM is selected.")).toBeInTheDocument();
  });

  it("says so when the selection names something the catalog does not offer", async () => {
    stubFetch({ activeProvider: "local", activeModel: "absent", providers: [ { name: "local", models: [] } ] });
    render(<LlmConfigWidget />);

    expect(await screen.findByText("No LLM is selected.")).toBeInTheDocument();
  });

  it("reports a failure to load, with a way to try again", async () => {
    stubFailingFetch(500, "Server Error");
    render(<LlmConfigWidget />);

    expect(await screen.findByText("The LLM configuration could not be loaded")).toBeInTheDocument();

    vi.unstubAllGlobals();
    stubFetch();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(screen.getByText("Local (Ollama)")).toBeInTheDocument());
  });
});
