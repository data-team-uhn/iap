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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import LlmConfigManager from "@iap/llm/LlmConfigManager";

interface RecordedRequest {
  url: string;
  method: string;
  params: URLSearchParams;
}

let requests: RecordedRequest[] = [];

const catalogJson = (activeProvider = "local", activeModel = "llama3.2-3b") => ({
  activeProvider,
  activeModel,
  providers: [
    {
      name: "local",
      label: "Local (Ollama)",
      endpoint: "http://localhost:11434/v1",
      timeoutSeconds: 600,
      models: [
        { name: "llama3.2-3b", maxOutputTokens: 1024, developer: "meta" },
        { name: "other-model", maxOutputTokens: 2048 },
      ],
    },
    { name: "prompter", endpoint: "https://prompter.example.invalid/v1", models: [ { name: "GPT-OSS-120B" } ] },
    { name: "empty", models: [] },
  ],
});

const stubFetch = (postStatus = 200) =>
  vi.stubGlobal("fetch", vi.fn((url: string, options?: RequestInit) => {
    const method = options?.method ?? "GET";
    const params = new URLSearchParams(options?.body as URLSearchParams | undefined);
    requests.push({ url, method, params });
    if (method === "GET") {
      return Promise.resolve({
        ok: true, status: 200, statusText: "OK", url,
        json: () => Promise.resolve(catalogJson()),
      } as unknown as Response);
    }
    return Promise.resolve({
      ok: postStatus < 400,
      status: postStatus,
      statusText: postStatus === 200 ? "OK" : "Error",
      url,
      json: () => Promise.resolve(
        catalogJson(params.get("activeProvider") ?? "", params.get("activeModel") ?? "")),
    } as unknown as Response);
  }));

const stubFailingFetch = (status: number, statusText: string) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText, url }) as unknown as Response)));

// MUI renders a select as a button showing the current value, which opens a listbox when clicked.
const chooseOption = async (selectName: string, option: string) => {
  fireEvent.mouseDown(screen.getByRole("combobox", { name: selectName }));
  const listbox = await screen.findByRole("listbox");
  fireEvent.click(within(listbox).getByRole("option", { name: option }));
};

const renderManager = () => render(<MemoryRouter><LlmConfigManager /></MemoryRouter>);

const loaded = async () => {
  renderManager();
  expect(await screen.findByRole("combobox", { name: "Provider" })).toBeInTheDocument();
};

const saveButton = () => screen.getByRole("button", { name: "Save" });

const lastPost = () => requests.filter(request => request.method === "POST").at(-1);

beforeEach(() => {
  requests = [];
});

afterEach(() => vi.unstubAllGlobals());

describe("LlmConfigManager", () => {
  it("shows the active selection and its settings", async () => {
    stubFetch();
    await loaded();

    expect(screen.getByRole("combobox", { name: "Provider" })).toHaveTextContent("Local (Ollama)");
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("llama3.2-3b");
    expect(screen.getByText("http://localhost:11434/v1")).toBeInTheDocument();
    expect(screen.getByText("1024")).toBeInTheDocument();
    expect(screen.getByText("meta")).toBeInTheDocument();
  });

  it("offers nothing to save until something changes", async () => {
    stubFetch();
    await loaded();

    expect(saveButton()).toBeDisabled();

    await chooseOption("Model", "other-model");

    expect(saveButton()).toBeEnabled();
  });

  it("moves to the provider's first model when the provider changes", async () => {
    stubFetch();
    await loaded();

    await chooseOption("Provider", "prompter");

    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("GPT-OSS-120B");
    expect(screen.getByText("https://prompter.example.invalid/v1")).toBeInTheDocument();
  });

  it("saves the selection and confirms it", async () => {
    stubFetch();
    await loaded();

    await chooseOption("Model", "other-model");
    fireEvent.click(saveButton());

    await waitFor(() => expect(lastPost()).toBeDefined());
    expect(lastPost()?.params.get("activeProvider")).toBe("local");
    expect(lastPost()?.params.get("activeModel")).toBe("other-model");
    expect(await screen.findByText("Active LLM updated")).toBeInTheDocument();
    await waitFor(() => expect(saveButton()).toBeDisabled());
  });

  it("reports a refused save without losing what was chosen", async () => {
    stubFetch(403);
    await loaded();

    await chooseOption("Model", "other-model");
    fireEvent.click(saveButton());

    expect(await screen.findByText("The selection could not be saved")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("other-model");
  });

  it("has nothing to choose from for a provider with no models", async () => {
    stubFetch();
    await loaded();

    await chooseOption("Provider", "empty");

    expect(screen.getByRole("combobox", { name: "Model" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("This provider offers no models.")).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });

  it("reports a failure to load, with a way to try again", async () => {
    stubFailingFetch(500, "Server Error");
    renderManager();

    expect(await screen.findByText("The LLM configuration could not be loaded")).toBeInTheDocument();

    vi.unstubAllGlobals();
    stubFetch();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("combobox", { name: "Provider" })).toHaveTextContent("Local (Ollama)");
  });

  it("says so when no provider is configured", async () => {
    stubFetch();
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
      ok: true, status: 200, statusText: "OK", url,
      json: () => Promise.resolve({ providers: [] }),
    } as unknown as Response)));
    renderManager();

    expect(await screen.findByText("No LLM providers are configured.")).toBeInTheDocument();
  });
});
