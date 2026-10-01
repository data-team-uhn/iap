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

import webpack from 'webpack';
import { WebpackAssetsManifest } from 'webpack-assets-manifest';
import MinimizerPlugin from 'minimizer-webpack-plugin';
import ESLintPlugin from 'eslint-webpack-plugin';
import { defineReactCompilerLoaderOption, reactCompilerLoader } from 'react-compiler-webpack';

import { createHash } from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const isProduction = process.argv.find(arg => arg.startsWith("--mode"))?.substring(7) == 'production';

const FRONTEND_RESOURCES_PATH = '/libs/iap/resources/';

// PDF.js runs in a web worker, served as a file of its own. Hashed so it caches like the chunks;
// .js because Sling serves .mjs as application/octet-stream.
const pdfWorkerBytes = fs.readFileSync(new URL(import.meta.resolve('pdfjs-dist/build/pdf.worker.min.mjs')));
const pdfWorkerHash = createHash('sha256').update(pdfWorkerBytes).digest('hex').slice(0, 20);
const PDF_WORKER_FILE = `pdf.worker.min.${pdfWorkerHash}.js`;

// A plugin that adds a file to the output as it is. The file belongs to no chunk, so it cannot take
// a chunk's key in assets.json.
function emitStaticAsset(name, content, info = {}) {
  return {
    apply: compiler => compiler.hooks.thisCompilation.tap(`Emit ${name}`, compilation =>
      compilation.hooks.processAssets.tap(
        { name: `Emit ${name}`, stage: compiler.webpack.Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL },
        () => compilation.emitAsset(name, new compiler.webpack.sources.RawSource(content), info)
      )
    )
  };
}

/**
 * Helper function to format and log React Compiler events
 * @param {string} filename - The full file path
 * @param {object} event - The compiler event object
 */
function logCompilerEvent(filename, event) {
  if (!event) return;
  const filePath = filename ?? event.filename ?? event.file ?? event.path ?? "(unknown file)";
  const fileName = filePath.replace(/^.*[\\/]/, '');

  const ANSI = {
    reset: '\x1b[0m',
    bold: '\x1b[1m',
    red: '\x1b[31m',
    yellow: '\x1b[33m',
    green: '\x1b[32m',
    cyan: '\x1b[36m',
    gray: '\x1b[90m',
  };

  const kind = event.kind;

  if (kind !== 'CompileError' && kind !== 'CompileSkip') return;

  const color =
    kind === 'CompileError' ? ANSI.red :
    kind === 'CompileSkip'  ? ANSI.yellow :
    ANSI.cyan;

  const sep = `${ANSI.gray}${'-'.repeat(70)}${ANSI.reset}`;
  // If it's a skip/error but has no details, still log a minimal line
  console.log(sep);
  console.log(`${ANSI.bold}${color}[React Compiler] ${kind} ${fileName}${ANSI.reset}`);

  const options = event.detail?.options;
  if (options) {
    const reason = options.reason;
    const category = options.category;
    const desc = options.description;
    const suggestions = options.suggestions;
    const message = options.details?.[0]?.message;
    const loc = options.loc ? options.loc : options.details[0].loc;

    if (reason || category) console.log(`[${category || "-"}]: ${reason || "-"}`);
    if (message) console.log(`Message: ${message}`);
    if (desc) console.log(`Description: ${desc}`);
    if (suggestions) console.log('Suggestions:', suggestions);
    if (loc) console.log(`${ANSI.bold}Location: Line ${loc.start.line}, Column ${loc.start.column}, identifierName ${loc.identifierName || "-"}${ANSI.reset}`);
  }
}

export default (env) => {
  return {
    experiments: {
      outputModule: true,
    },
    mode: 'development',
    devtool: 'source-map',
    cache: {
      type: 'filesystem',
      // Invalidate the cache when the (generated) config changes, e.g. when a new entry
      // point is added; everything else is invalidated by webpack's own file tracking
      buildDependencies: {
        config: [__filename]
      }
    },
    infrastructureLogging: {
      level: 'error' // Mask Webpack infrastructure-level warnings to silence warning when React Compiler errors on serialisation of Webpack’s persistent cache
    },
    entry: {
ENTRY_CONTENT
    },
    plugins: [
      new WebpackAssetsManifest({
        output: "assets.json",
        // header.html loads the page from these two keys, so fail the build if another file took one
        done: (manifest, stats) => {
          for (const chunk of ['vendor', 'runtime']) {
            const target = manifest.get(`${chunk}.js`);
            if (!new RegExp(`^${chunk}\\.[0-9a-f]+\\.js$`).test(String(target))) {
              stats.compilation.errors.push(new webpack.WebpackError(
                `assets.json maps ${chunk}.js to ${String(target)} instead of the ${chunk} chunk`));
            }
          }
          return Promise.resolve();
        }
      }),
      // The MUI X license key belongs to the deployment rather than to this (public) repository,
      // so it is read from the build environment and substituted in here.
      new webpack.DefinePlugin({
        "process.env.MUI_LICENSE_KEY": JSON.stringify(process.env.MUI_LICENSE_KEY),
        // Where pdfjsClient.ts finds the worker emitted below
        "process.env.PDF_WORKER_URL": JSON.stringify(FRONTEND_RESOURCES_PATH + PDF_WORKER_FILE),
      }),
      // The PDF.js worker: already minified, and a module, so the minimizer leaves it alone
      emitStaticAsset(PDF_WORKER_FILE, pdfWorkerBytes, { minimized: true, javascriptModule: true }),
      // The client-side assetManager fetches an asset *dependencies* manifest alongside the
      // assets.json name map (see frontend-commons/src/assetManager.tsx). No IAP entry point
      // declares runtime dependencies on other entry points, so emit an empty manifest to
      // keep that fetch from 404ing; when real cross-entry dependencies appear, replace this
      // with a proper per-module declaration + aggregation step.
      emitStaticAsset('assetDependencies.json', '{}\n'),
      !env.quick && new ESLintPlugin({
        extensions: ['js', 'jsx', 'ts', 'tsx'],
        emitWarning: false,   // Show warnings in ESLint output, not as webpack warnings
        failOnError: true,  // Break build on ESLint error
      }),
    ],
    module: {
      rules: [
        {
          test: /\.(js|jsx|ts|tsx)$/,
          exclude: /node_modules/,
          resolve: { fullySpecified: false }, // disable ESM fully specified
          use: [
            { loader: 'babel-loader' },
            {
              loader: reactCompilerLoader,
              options: defineReactCompilerLoaderOption({
                compilationMode : 'annotation',
                logger: {
                  logEvent(filename, event) {
                    logCompilerEvent(filename, event);
                  }
                }
              })
            }
          ]
        },
        {
          test:/\.css$/,
          use:['style-loader','css-loader']
        }
      ]
    },
    resolve: {
      // Cross-module imports use the @iap/<module>/... namespace; each module is aggregated
      // into its own src/<module>/ subdirectory, so a single mapping covers all of them
      alias: {
        '@iap': path.resolve(__dirname, 'src')
      },
      extensions: ['.js', '.jsx', '.ts', '.tsx', '...']
    },
    optimization: {
      usedExports: true,
      // Recompute [contenthash] from each asset's final bytes. Without this (it is on only in
      // production mode by default), an entry whose own modules are unchanged keeps its old
      // filename even when the chunk hashes it references change — and since hashed assets are
      // served as immutable (IAP-86), browsers then keep loading the cached old entry, which
      // points at the previous build's chunks: redeployed code never reaches the user.
      realContentHash: true,
      minimize: isProduction,
      minimizer: [
        new MinimizerPlugin({
          minimizerOptions: {
            mangle: {
              reserved: ['$super']
            }
          }
        })
      ],
      runtimeChunk: 'single',
      splitChunks: {
        chunks: 'all',
        cacheGroups: {
          // PDF.js is only needed once a PDF is opened, so it gets a chunk of its own rather than
          // joining the vendor chunk that every page loads
          pdfjs: {
            test: /[\\/]node_modules[\\/]pdfjs-dist[\\/]/,
            chunks: 'async',
            name: 'pdfjs',
            enforce: true,
            priority: 0
          },
          defaultVendors: {
            minChunks: 1,
            minSize: 200,
            test: /[\\/]node_modules[\\/]/,
            name: 'vendor',
            enforce: true,
            priority: -10
          },
          default: {
            minChunks: 2,
            minSize: 1000,
            name: false,
            priority: -20,
            reuseExistingChunk: true
          }
        }
      }
    },
    output: {
      clean: true,
      library: {
        type: "modern-module",
      },
      path: __dirname + '/dist/SLING-INF/content/libs/iap/resources/',
      // Where the runtime loads a chunk from when the code asks for it later, like the PDF.js one
      publicPath: FRONTEND_RESOURCES_PATH,
      filename: '[name].[contenthash].js',
    }
  }
};
