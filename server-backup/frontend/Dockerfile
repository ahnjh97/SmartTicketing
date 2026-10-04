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
RUN test -n "$VITE_KAKAO_MAP_JS_KEY" && npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
