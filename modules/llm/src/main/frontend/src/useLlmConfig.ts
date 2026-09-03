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

import { useCallback, useEffect, useState } from "react";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";

import { EMPTY_CATALOG, parseCatalog, type LlmCatalog } from "./llmConfigModel";

// The configuration servlet: the LLM configuration node, asked for with the `llm` selector. A GET
// answers with the catalog, a POST switches the active selection and answers with the refreshed
// catalog.
export const CONFIG_URL = "/apps/iap/config/LLM.llm.json";

const checkOk = (response: Response): Response => {
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Every exchange goes through here, so an unreachable server, a refused write or an unreadable
// answer reaches the screen already worded for whoever will read it.
const reporting = async <T>(exchange: () => Promise<T>): Promise<T> => {
  try {
    return await exchange();
  } catch (error: unknown) {
    throw new Error(describeRequestFailure(error));
  }
};

// The single owner of the LLM configuration I/O: it holds the catalog and the one write the screen
// offers. Both the load and the save take their new state from what the servlet answered, rather
// than from what was asked for, so the screen always shows what was actually stored.
export function useLlmConfig() {
  const [ catalog, setCatalog ] = useState<LlmCatalog>(EMPTY_CATALOG);
  const [ loading, setLoading ] = useState(true);
  const [ loadError, setLoadError ] = useState<string>();
  const authenticatedFetch = useAuthenticatedFetch();

  const fetchCatalog = useCallback((): Promise<LlmCatalog> =>
    reporting(async () => {
      const response = checkOk(await authenticatedFetch(CONFIG_URL));
      return parseCatalog(await response.json());
    }), [authenticatedFetch]);

  const reload = useCallback((): Promise<void> =>
    fetchCatalog()
      .then(loaded => {
        setCatalog(loaded);
        setLoadError(undefined);
      })
      .catch((error: unknown) => {
        setLoadError(messageOf(error));
      })
      .finally(() => setLoading(false)), [fetchCatalog]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Switches the active provider and model. A failure is thrown rather than stored: the save is an
  // action the screen reports on its own, unlike a load failure, which is a state of the screen.
  const save = useCallback(async (provider: string, model: string): Promise<void> => {
    const body = new URLSearchParams({ activeProvider: provider, activeModel: model });
    const saved = await reporting(async () => {
      const response = checkOk(await authenticatedFetch(CONFIG_URL, { method: "POST", body }));
      return parseCatalog(await response.json());
    });
    setCatalog(saved);
    setLoadError(undefined);
  }, [authenticatedFetch]);

  return { catalog, loading, loadError, reload, save };
}
