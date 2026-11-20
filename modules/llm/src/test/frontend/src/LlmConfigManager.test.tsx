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

import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { jsonResponse, sentRequests, stubFailingFetch, stubFetch } from "@iap/frontend-commons/fetch.fixture";
import LlmConfigManager from "@iap/llm/LlmConfigManager";

const catalogJson = (activeProvider = "example", activeModel = "model-a") => ({
  activeProvider,
  activeModel,
  providers: [
    {
      name: "example",
      label: "Example Provider",
      endpoint: "http://model-a.example.invalid/v1",
      timeoutSeconds: 600,
      models: [
        { name: "model-a", contextLimitTokens: 1024, developer: "meta" },
        { name: "other-model", contextLimitTokens: 2048 },
      ],
    },
    { name: "prompter", endpoint: "https://prompter.example.invalid/v1", models: [ { name: "GPT-OSS-120B" } ] },
    { name: "empty", models: [] },
  ],
});

// Answers the catalog GET, and a POST with the catalog as it would be after the switch
const stubCatalog = (postStatus = 200) => stubFetch(request => request.method === "GET"
  ? jsonResponse(catalogJson())
  : jsonResponse(catalogJson(request.params.get("activeProvider") ?? "", request.params.get("activeModel") ?? ""),
    postStatus));

// MUI renders a select as a button showing the current value, which opens a listbox when clicked.
const chooseOption = async (selectName: string, option: string) => {
  fireEvent.mouseDown(screen.getByRole("combobox", { name: selectName }));
  const listbox = await screen.findByRole("listbox");
  fireEvent.click(within(listbox).getByRole("option", { name: option }));
};

const renderManager = () => render(<MemoryRouter><LlmConfigManager /></MemoryRouter>, { wrapper: NoticeProvider });

const loaded = async () => {
  renderManager();
  expect(await screen.findByRole("combobox", { name: "Provider" })).toBeInTheDocument();
};

const saveButton = () => screen.getByRole("button", { name: "Save" });

const lastPost = () => sentRequests().filter(request => request.method === "POST").at(-1);

afterEach(() => vi.unstubAllGlobals());

describe("LlmConfigManager", () => {
  it("shows the active selection and its settings", async () => {
    stubCatalog();
    await loaded();

    expect(screen.getByRole("combobox", { name: "Provider" })).toHaveTextContent("Example Provider");
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("model-a");
    expect(screen.getByText("http://model-a.example.invalid/v1")).toBeInTheDocument();
    expect(screen.getByText("1024")).toBeInTheDocument();
    expect(screen.getByText("meta")).toBeInTheDocument();
  });

  it("offers nothing to save until something changes", async () => {
    stubCatalog();
    await loaded();

    expect(saveButton()).toBeDisabled();

    await chooseOption("Model", "other-model");

    expect(saveButton()).toBeEnabled();
  });

  it("moves to the provider's first model when the provider changes", async () => {
    stubCatalog();
    await loaded();

    await chooseOption("Provider", "prompter");

    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("GPT-OSS-120B");
    expect(screen.getByText("https://prompter.example.invalid/v1")).toBeInTheDocument();
  });

  it("saves the selection and confirms it", async () => {
    stubCatalog();
    await loaded();

    await chooseOption("Model", "other-model");
    fireEvent.click(saveButton());

    await waitFor(() => expect(lastPost()).toBeDefined());
    expect(lastPost()?.params.get("activeProvider")).toBe("example");
    expect(lastPost()?.params.get("activeModel")).toBe("other-model");
    expect(await screen.findByText("Active LLM updated")).toBeInTheDocument();
    await waitFor(() => expect(saveButton()).toBeDisabled());
  });

  it("reports a refused save without losing what was chosen", async () => {
    stubCatalog(403);
    await loaded();

    await chooseOption("Model", "other-model");
    fireEvent.click(saveButton());

    expect(await screen.findByText("The selection could not be saved")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Model" })).toHaveTextContent("other-model");
  });

  it("has nothing to choose from for a provider with no models", async () => {
    stubCatalog();
    await loaded();

    await chooseOption("Provider", "empty");

    expect(screen.getByRole("combobox", { name: "Model" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("This provider offers no models.")).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
  });

  it("reports a failure to load, with a way to try again", async () => {
    stubFailingFetch(500);
    renderManager();

    expect(await screen.findByText("The LLM configuration could not be loaded")).toBeInTheDocument();

    vi.unstubAllGlobals();
    stubCatalog();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("combobox", { name: "Provider" })).toHaveTextContent("Example Provider");
  });

  it("says so when no provider is configured", async () => {
    stubFetch(() => jsonResponse({ providers: [] }));
    renderManager();

    expect(await screen.findByText("No LLM providers are configured.")).toBeInTheDocument();
  });
});
