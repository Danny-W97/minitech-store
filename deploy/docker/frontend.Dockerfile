FROM nginx:1.29-alpine

RUN apk upgrade --no-cache

COPY deploy/docker/nginx.frontend.conf /etc/nginx/conf.d/default.conf
COPY frontend/ /usr/share/nginx/html/

EXPOSE 8080
