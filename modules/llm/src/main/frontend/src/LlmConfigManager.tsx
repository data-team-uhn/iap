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

import { useState } from "react";

import {
  Button, Card, CardContent, MenuItem, Stack, TextField, Typography,
} from "@mui/material";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import NoticeSnackbar, { type Notice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import {
  findModel, findProvider, firstModelName, providerLabel, type LlmSetting,
} from "./llmConfigModel";
import { useLlmConfig } from "./useLlmConfig";

// The settings of the selected provider or model, read-only: they are seeded from initial content
// and are not edited here, but an administrator choosing between models needs to see what each one
// actually is - which endpoint, which token limits, which temperature.
function SettingList({ title, settings }: { title: string; settings: LlmSetting[] }) {
  return (
    <Card variant="outlined" sx={{ flex: 1, minWidth: 0 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>{title}</Typography>
        { settings.length === 0
          ? <Typography color="textSecondary" variant="body2">No settings.</Typography>
          : settings.map(setting => (
            <Stack key={setting.name} direction="row" spacing={2} sx={{ justifyContent: "space-between", py: 0.25 }}>
              <Typography color="textSecondary" variant="body2">{setting.name}</Typography>
              <Typography variant="body2" sx={{ overflowWrap: "anywhere", textAlign: "right" }}>
                {setting.value}
              </Typography>
            </Stack>
          )) }
      </CardContent>
    </Card>
  );
}

// The administration screen for choosing which LLM the application talks to. The catalog of
// providers and models is seeded from initial content; the only thing changed here is which of them
// is active, which is what every LLM call then resolves through.
function LlmConfigManager() {
  const { catalog, loading, loadError, reload, save } = useLlmConfig();
  // What has been picked since the server last answered. Nothing picked means the screen simply
  // shows what is active, so a load or a save both leave it showing what is actually stored.
  const [ picked, setPicked ] = useState<{ provider: string; model: string }>();
  const [ notice, setNotice ] = useState<Notice>();

  const provider = picked?.provider ?? catalog.activeProvider ?? "";
  const model = picked?.model ?? catalog.activeModel ?? "";

  const selectedProvider = findProvider(catalog, provider);
  const selectedModel = findModel(selectedProvider, model);
  const changed = provider !== (catalog.activeProvider ?? "") || model !== (catalog.activeModel ?? "");

  const { working, run } = useAsyncAction<never>({
    // Reported through the snackbar below rather than as a returned failure, so that a save keeps
    // the same voice as the rest of the screen
    onFailure: error => {
      setNotice({ title: "The selection could not be saved", message: messageOf(error) });
      return undefined;
    },
    onSuccess: () => {
      // Back to following the server, which has just answered with the selection it stored
      setPicked(undefined);
      setNotice({ title: "Active LLM updated", severity: "success" });
    },
  });

  // Changing provider invalidates the model, since models belong to one provider.
  const changeProvider = (name: string) =>
    setPicked({ provider: name, model: firstModelName(findProvider(catalog, name)) ?? "" });

  return (
    <AdminScreen
      title="LLM configuration"
      action={
        <Button
          variant="contained"
          loading={working}
          disabled={!changed || model === ""}
          onClick={() => run(() => save(provider, model))}
        >
          Save
        </Button>
      }
    >
      <LoadingOverlay open={loading} />
      { loadError
        && (
          <LoadError
            title="The LLM configuration could not be loaded"
            message={loadError}
            onRetry={reload}
            sx={{ mb: 2 }}
          />
        )}
      { !loading && catalog.providers.length === 0 && !loadError
        && <Typography color="textSecondary">No LLM providers are configured.</Typography> }
      { catalog.providers.length > 0
        && (
          <Stack spacing={3}>
            <Typography color="textSecondary" variant="body2">
              Every request goes to the provider and model selected here.
            </Typography>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
              <TextField
                select
                label="Provider"
                value={provider}
                onChange={event => changeProvider(event.target.value)}
                sx={{ minWidth: 240 }}
              >
                { catalog.providers.map(entry => (
                  <MenuItem key={entry.name} value={entry.name}>{providerLabel(entry)}</MenuItem>
                )) }
              </TextField>
              <TextField
                select
                label="Model"
                value={model}
                onChange={event => setPicked({ provider, model: event.target.value })}
                disabled={!selectedProvider || selectedProvider.models.length === 0}
                helperText={selectedProvider?.models.length === 0
                  ? "This provider offers no models."
                  : undefined}
                sx={{ minWidth: 240 }}
              >
                { selectedProvider?.models.map(entry => (
                  <MenuItem key={entry.name} value={entry.name}>{entry.name}</MenuItem>
                )) }
              </TextField>
            </Stack>
            <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
              { selectedProvider && <SettingList title="Provider settings" settings={selectedProvider.settings} /> }
              { selectedModel && <SettingList title="Model settings" settings={selectedModel.settings} /> }
            </Stack>
          </Stack>
        )}
      <NoticeSnackbar notice={notice} onClose={() => setNotice(undefined)} />
    </AdminScreen>
  );
}

export default LlmConfigManager;
