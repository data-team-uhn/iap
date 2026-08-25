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

import { CircularProgress, Stack, Typography } from "@mui/material";

import LoadError from "@iap/frontend-commons/components/LoadError";

import { findModel, findProvider, providerLabel } from "./llmConfigModel";
import { useLlmConfig } from "./useLlmConfig";

// One line of the summary: what it is, and what it currently says.
function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" spacing={2} sx={{ justifyContent: "space-between" }}>
      <Typography color="textSecondary" variant="body2">{label}</Typography>
      <Typography variant="body2" sx={{ overflowWrap: "anywhere", textAlign: "right" }}>{value}</Typography>
    </Stack>
  );
}

// The administration console widget summarizing which LLM the application is talking to. Read-only:
// changing the selection is behind the widget frame's "Configure LLM" action (see the extension
// node), which opens the full screen.
function LlmConfigWidget() {
  const { catalog, loading, loadError, reload } = useLlmConfig();

  if (loading) {
    return <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />;
  }
  if (loadError) {
    return <LoadError title="The LLM configuration could not be loaded" message={loadError} onRetry={reload} />;
  }

  const activeProvider = findProvider(catalog, catalog.activeProvider);
  const activeModel = findModel(activeProvider, catalog.activeModel);
  if (!activeProvider || !activeModel) {
    return <Typography color="textSecondary" variant="body2">No LLM is selected.</Typography>;
  }

  const models = catalog.providers.reduce((total, provider) => total + provider.models.length, 0);

  return (
    <Stack spacing={0.5}>
      <SummaryRow label="Provider" value={providerLabel(activeProvider)} />
      <SummaryRow label="Model" value={activeModel.name} />
      <SummaryRow
        label="Available"
        value={`${catalog.providers.length} providers, ${models} models`}
      />
    </Stack>
  );
}

export default LlmConfigWidget;
