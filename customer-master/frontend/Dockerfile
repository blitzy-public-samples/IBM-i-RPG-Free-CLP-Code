# Customer Master frontend image: the built React SPA served by nginx, which
# also reverse-proxies /api to the backend, so the browser talks to one origin.
#
# The build context is customer-master/frontend, so the OpenAPI document in
# ../openapi lies outside it: `npm run gen:api` never runs here, and the build
# type-checks against the committed src/api/schema.d.ts.
#
# The SPA reads no runtime configuration: it calls the API through its own
# origin (/api). nginx.conf, baked into the image, names the backend by its
# Compose service name, app:8080.

FROM node:24.21.0-alpine AS build

WORKDIR /app

ENV NPM_CONFIG_UPDATE_NOTIFIER=false

# devDependencies are installed on purpose: `npm run build` needs TypeScript,
# Vite and the test types `tsc -b` checks, and none reaches the runtime stage,
# so neither NODE_ENV=production nor --omit=dev is set. The lock file carries
# the musl bindings of rolldown and lightningcss, so Alpine needs no native
# rebuild.
#
# --ignore-scripts leaves the installed tree unchanged: on Linux only fsevents
# (macOS-only, never installed) and the msw copy nested under vitest, whose
# postinstall needs an msw.workerDirectory that package.json does not set,
# have install scripts. The BuildKit cache mount keeps npm's download cache,
# safe to share between concurrent builds; the built-in Dockerfile frontend
# supports it, so no `# syntax=` directive or frontend image pull is needed.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --ignore-scripts

COPY . .
RUN npm run build

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

# nginx.conf is a server block: the image's /etc/nginx/nginx.conf includes
# conf.d/*.conf inside its `http` block, so this copy replaces the default
# server and keeps the image's events, MIME types and worker settings. --chmod
# gives the packaged default.conf's mode whatever the checkout's umask, so the
# layer is the same on every machine.
COPY --chmod=644 nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
