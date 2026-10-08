# Customer Master frontend image: the built React SPA served by nginx, which
# also reverse-proxies /api to the backend, so the browser talks to one origin.
#
# Compose service `frontend` builds this file with `build.context: ./frontend`,
# waits for `app` to report healthy, publishes container port 80 on host port
# FRONTEND_PORT (default 8080) and defines the healthcheck:
#   wget -q -O /dev/null http://127.0.0.1/healthz && wget -q -O /dev/null http://127.0.0.1/
# The first request proves nginx answers (nginx.conf `location = /healthz`),
# the second that the built index.html is served. Compose owns that check, so
# this file declares no HEALTHCHECK, and it relies on the BusyBox wget that
# nginx:alpine ships, so no package is removed from the runtime base.
#
# Build context: customer-master/frontend only. .dockerignore keeps the host's
# node_modules/ and dist/ out of it, so dependencies are always installed from
# package-lock.json and the bundle is always built here, never copied from a
# host build. The OpenAPI document in ../openapi lies outside the context, so
# `npm run gen:api` is never run in the image: src/api/schema.d.ts is generated
# on the host and committed, and the build type-checks against that copy.
#
# Configuration: none is baked in and none is read at run time. The SPA calls
# the API through its own origin (/api), message texts come from
# GET /api/messages, and nginx.conf names the backend by its Compose service
# name (`app:8080`); host ports are Compose's business and never appear here.
#
# BuildKit is required for the npm cache mount in the build stage. Compose v2
# and `docker build` on current Docker Engine use it by default. No `# syntax=`
# directive is declared: the built-in Dockerfile frontend supports cache
# mounts, so a build never has to pull a frontend image.

# ---------------------------------------------------------------------------
# Stage 1: install the locked dependencies and build dist/ with Vite.
# ---------------------------------------------------------------------------
FROM node:24.21.0-alpine AS build

WORKDIR /app

# npm runs non-interactively and never asks the registry whether a newer npm
# exists; the toolchain is the one pinned by the image tag above.
ENV NPM_CONFIG_UPDATE_NOTIFIER=false

# Layer 1: dependencies. Only a change to package.json or package-lock.json
# invalidates it, so a source-only change reuses the installed node_modules.
#
# devDependencies are installed on purpose: `npm run build` needs TypeScript
# (`tsc -b`), Vite and @vitejs/plugin-react, and `tsc -b` also type-checks the
# colocated *.test.ts(x) suites, which import Vitest, Testing Library and MSW
# types. NODE_ENV=production and --omit=dev would make the build fail, and
# neither reaches the runtime stage, which holds only the static bundle.
#
# The lock file carries the musl bindings of rolldown and lightningcss, so the
# install resolves the native packages Alpine needs without a rebuild.
#
# --ignore-scripts: no third-party lifecycle script runs during the image
# build. On Linux the lock file holds only two packages with install scripts:
# fsevents, a macOS-only optional dependency that is never installed here, and
# the msw copy nested under vitest, whose postinstall only copies its service
# worker into a `msw.workerDirectory` that package.json does not configure. The
# installed tree is therefore the same with or without the scripts.
#
# The cache mount keeps npm's content-addressed download cache across builds,
# so a lock-file change downloads only the packages it changes. npm's cache is
# safe for concurrent builds that share the mount.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --ignore-scripts

# Layer 2: sources and build configuration (index.html, src/, vite.config.ts,
# tsconfig*.json, eslint.config.js). `npm run build` runs `tsc -b && vite
# build`: a type error anywhere under src/ fails the image build, and Vite
# writes the bundle to its default output directory, /app/dist. tsc -b keeps
# its build info under node_modules/.tmp, which never reaches the runtime stage.
COPY . .
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2: runtime. nginx serving dist/ with the project's server block.
# ---------------------------------------------------------------------------
FROM nginx:1.30.5-alpine

LABEL org.opencontainers.image.title="customer-master-frontend" \
      org.opencontainers.image.description="Customer Master single-page application served by nginx, with /api proxied to the backend service app:8080"

# Worker count. The image's nginx.conf sets `worker_processes auto`, which
# starts one worker per CPU the kernel reports and ignores a container CPU limit
# (on a large host that is over a hundred workers for a static bundle and one
# proxy route). This switch enables the image's own entrypoint hook
# (/docker-entrypoint.d/30-tune-worker-processes.sh), which rewrites the
# directive at start-up to the smallest of the online CPUs, the cgroup cpuset
# and the cgroup CPU quota. Without a limit (`cpus:` in Compose, `--cpus` in
# `docker run`) the result equals `auto`; with one, nginx follows it. The
# worker settings stay the image's own; only its built-in tuning is switched on.
ENV NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE=1

# The stock welcome page and 50x.html are removed so the document root holds
# exactly the built bundle; nginx.conf does not reference 50x.html.
RUN find /usr/share/nginx/html -mindepth 1 -delete

# nginx.conf is a server block, not a full nginx.conf: the image's
# /etc/nginx/nginx.conf includes conf.d/*.conf inside its `http` block, so this
# copy replaces the image's default server and keeps its events, MIME types and
# worker settings. Because default.conf no longer matches the packaged file,
# the image's entrypoint script leaves it alone and adds no IPv6 listener;
# nginx.conf listens on IPv4 port 80 only. --chmod gives the file the mode of
# the packaged default.conf whatever the checkout's umask, so the layer is the
# same on every machine.
COPY --chmod=644 nginx.conf /etc/nginx/conf.d/default.conf

# index.html plus the hashed assets under assets/. Vite creates these files in
# the build stage, so they are root-owned and world-readable, which the nginx
# worker processes (user `nginx`) need in order to serve them.
COPY --from=build /app/dist /usr/share/nginx/html

# nginx.conf `listen 80`. The image's ENTRYPOINT (/docker-entrypoint.sh), CMD
# (`nginx -g 'daemon off;'`) and STOPSIGNAL (SIGQUIT, a graceful shutdown) are
# kept as the base image defines them: the master process runs as root to bind
# port 80 and the workers run as the unprivileged `nginx` user.
EXPOSE 80
