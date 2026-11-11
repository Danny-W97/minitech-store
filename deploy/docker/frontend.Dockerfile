FROM nginx:stable-alpine@sha256:0e52bd054126cdc040b1276c0af347aeb02a41b9cfe305d6094767ac30e97949

# Reviewed, signed APKs from the matching Alpine branch; no mutable apk upgrade.
ADD --checksum=sha256:62a7e37e43bbfc1f8525a00ad2125fd5897135dc93b849733884cb78ffd8e26c https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/brotli-libs-1.2.1-r0.apk /tmp/a44-brotli-libs.apk
ADD --checksum=sha256:531ae298451a4c1c44f563fc3b2eedc2c1d7e0a26a33bdfc0efe6faaff6a5942 https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/curl-8.23.0-r0.apk /tmp/a44-curl.apk
ADD --checksum=sha256:1f70ca404818805a6465b615bbfe84ef861488a963d7e630108ebd7fbaa51665 https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/libcurl-8.23.0-r0.apk /tmp/a44-libcurl.apk
RUN --network=none apk add --no-network /tmp/a44-brotli-libs.apk /tmp/a44-libcurl.apk /tmp/a44-curl.apk \
    && rm -f /tmp/a44-*.apk

COPY deploy/docker/nginx.frontend.conf /etc/nginx/conf.d/default.conf
COPY deploy/docker/nginx.conf /etc/nginx/nginx.conf
COPY frontend/ /usr/share/nginx/html/

EXPOSE 8080
USER 10001:10001
