# ===========================================================================
# Dockerfile frontend – Vite build + nginx (Phase 6)
# Build:  docker compose -f server/docker-compose.yml build frontend
# ===========================================================================
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL
ENV VITE_API_URL=
RUN npm run build

FROM nginx:alpine
# Custom nginx config: proxy /api va /ws toi backend
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
