# The default deploy instructions (https://biffweb.com/docs/reference/production/) don't
# use Docker, but this file is provided in case you'd like to deploy with containers.
#
# When running the container, make sure you set any environment variables defined in config.env,
# e.g. using whatever tools your deployment platform provides for setting environment variables.
from clojure:temurin-17-tools-deps-bullseye

ENV TAILWIND_VERSION=v3.2.4

RUN apt-get update && apt-get install -y \
  curl default-jre \
  && rm -rf /var/lib/apt/lists/*
RUN curl -L -o /usr/local/bin/tailwindcss \
  https://github.com/tailwindlabs/tailwindcss/releases/download/$TAILWIND_VERSION/tailwindcss-linux-x64 \
  && chmod +x /usr/local/bin/tailwindcss

# Install MinIO (used for non-prod S3-compatible storage)
RUN curl -L -o /usr/local/bin/minio https://dl.min.io/server/minio/release/linux-amd64/minio \
  && chmod +x /usr/local/bin/minio \
  && curl -L -o /usr/local/bin/mc https://dl.min.io/client/mc/release/linux-amd64/mc \
  && chmod +x /usr/local/bin/mc

WORKDIR /app
COPY src ./src
COPY dev ./dev
COPY resources ./resources
COPY deps.edn .
COPY docker-entrypoint.sh .

RUN clj -Mdev uberjar && cp target/jar/app.jar . && rm -r target
RUN rm -r /usr/local/bin/tailwindcss src dev resources deps.edn
RUN chmod +x docker-entrypoint.sh

EXPOSE 8080

ENV BIFF_PROFILE=prod
ENTRYPOINT ["./docker-entrypoint.sh"]
CMD ["/usr/bin/java", "-XX:-OmitStackTraceInFastThrow", "-XX:+CrashOnOutOfMemoryError", "-jar", "app.jar"]
