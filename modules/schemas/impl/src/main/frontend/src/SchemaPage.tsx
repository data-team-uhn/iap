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

import { type ReactNode, useCallback, useMemo } from "react";

import { Alert } from "@mui/material";
import { useLocation, useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import TagChip from "@iap/tags/TagChip";

import SchemaActions from "./SchemaActions";
import { schemaNameFromRoute, tagsOf, titleOf, versionNameFromRoute } from "./schemaModel";
import SchemaVersionComparison from "./SchemaVersionComparison";
import SchemaVersionList from "./SchemaVersionList";
import { strings } from "./schemaVersionTreeModel";
import SchemaVersionView from "./SchemaVersionView";
import { useSchema } from "./useSchema";

// What stays true of the schema on its page and on each of its versions' pages
function SchemaNotices({ schema, loadError, reload }: {
  schema: SerializedNode;
  loadError?: string;
  reload: () => Promise<void>;
}) {
  return (
    <>
      { loadError && <LoadError title="The schema could not be reloaded" message={loadError} onRetry={reload}
        sx={{ mb: 2 }} /> }
      { tagsOf(schema).includes("retired") && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          This schema is retired. None of its versions accepts new submissions.
        </Alert>
      ) }
    </>
  );
}

// One schema's page: its versions and where each stands, and the lifecycle actions on them and on
// the schema as a whole. With a version named, that version's own page; with another to compare it with too, their
// comparison, on the fields that the workflow definitions its extension names describe.
function SchemaPage({ extension }: { extension?: Record<string, unknown> }) {
  const { pathname, search } = useLocation();
  const versionName = versionNameFromRoute(pathname);
  const comparedWith = new URLSearchParams(search).get("compare");
  const navigate = useNavigate();
  const name = schemaNameFromRoute(pathname);
  const { schema, loading, loadError, reload } = useSchema(name);
  const reloadSchema = useCallback(() => void reload(), [ reload ]);
  // Kept the same while the extension is, so that the version list does not redraw its actions for nothing
  const comparisonDefaults = useMemo(() => strings(extension?.comparisonDefaults), [ extension ]);

  if (!schema) {
    return (
      <AdminScreen title="Schema">
        <LoadingOverlay open={loading} />
        { loadError && <LoadError title="The schema could not be loaded" message={loadError} onRetry={reload} /> }
      </AdminScreen>
    );
  }

  const notices: ReactNode = <SchemaNotices schema={schema} loadError={loadError} reload={reload} />;

  if (versionName && comparedWith) {
    return (
      <SchemaVersionComparison schema={schema} names={[ comparedWith, versionName ]} pageNotices={notices}
        definitions={{
          version: strings(extension?.comparisonVersionFieldsFrom),
          part: strings(extension?.comparisonPartFieldsFrom),
          option: strings(extension?.comparisonOptionFieldsFrom),
        }} />
    );
  }

  if (versionName) {
    return (
      <SchemaVersionView schema={schema} versionName={versionName} pageNotices={notices}
        reloadSchema={reloadSchema} comparisonDefaults={comparisonDefaults} />
    );
  }

  return (
    <AdminScreen
      title={titleOf(schema)}
      status={<TagChip tags={schema.tags} />}
      description={"A draft version can change in any way. Once active, only its wording can change. Create a "
        + "new version to change anything else."}
      action={<SchemaActions schema={schema} reload={reload}
        removed={() => void navigate("/admin/schemas")} />}
      disablePanel
    >
      {notices}
      <SchemaVersionList schema={schema} reload={reloadSchema} comparisonDefaults={comparisonDefaults} />
    </AdminScreen>
  );
}

export default SchemaPage;
