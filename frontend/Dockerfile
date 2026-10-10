FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci
COPY . .

ARG VITE_API_BASE_URL=
ARG VITE_BASE_PATH=/
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_BASE_PATH=$VITE_BASE_PATH
ARG VITE_KAKAO_MAP_JS_KEY
ENV VITE_KAKAO_MAP_JS_KEY=$VITE_KAKAO_MAP_JS_KEY
ARG VITE_TOSS_CLIENT_KEY=
ENV VITE_TOSS_CLIENT_KEY=$VITE_TOSS_CLIENT_KEY
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-config.sh
