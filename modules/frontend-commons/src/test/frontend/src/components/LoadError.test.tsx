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
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import LoadError from "@iap/frontend-commons/components/LoadError";

import type { ComponentProps } from "react";

const renderLoadError = (props: Partial<ComponentProps<typeof LoadError>> = {}) => {
  const onRetry = props.onRetry ?? vi.fn().mockResolvedValue(undefined);
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <LoadError
        title="The widgets could not be loaded"
        message="The server ran into a problem and could not complete this. (HTTP 500)"
        {...props}
        onRetry={onRetry}
      />
    </ThemeProvider>
  );
  return { onRetry };
};

describe("LoadError", () => {
  it("says what could not be loaded, and why", () => {
    renderLoadError();

    const report = screen.getByRole("alert");
    expect(report).toHaveTextContent("The widgets could not be loaded");
    expect(report).toHaveTextContent("(HTTP 500)");
  });

  it("says only what could not be loaded when the cause is unknown", () => {
    renderLoadError({ message: undefined });

    expect(screen.getByRole("alert")).toHaveTextContent("The widgets could not be loaded");
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("fetches again when the retry is taken up", async () => {
    const { onRetry } = renderLoadError();

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => { expect(onRetry).toHaveBeenCalled(); });
  });

  it("shows the attempt in progress, and takes no second instruction while it runs", async () => {
    const settle: { resolve?: () => void } = {};
    const onRetry = vi.fn(() => new Promise<void>(resolve => { settle.resolve = resolve; }));
    renderLoadError({ onRetry });

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => { expect(screen.getByRole("button", { name: "Retry" })).toBeDisabled(); });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    settle.resolve?.();
    await waitFor(() => { expect(screen.getByRole("button", { name: "Retry" })).toBeEnabled(); });
  });
});
