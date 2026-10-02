# syntax=docker/dockerfile:1.7

FROM oraclelinux:9-slim AS truffleruby-base

ARG TARGETARCH
ARG TRUFFLERUBY_VERSION=34.0.1

RUN microdnf install -y \
      ca-certificates curl gzip tar xz \
      gcc gcc-c++ make pkgconf-pkg-config \
      openssl-devel libffi-devel \
    && microdnf clean all

RUN set -eux; \
    case "$TARGETARCH" in \
      amd64) TR_ARCH=amd64; TR_SHA256=d73540e36d457555d57c7bff7c35805d05388912fb54a880dea10cc44d001f41 ;; \
      arm64) TR_ARCH=aarch64; TR_SHA256=8b1156eda10f3a00c56544f91c3f06735db49fe5850d801bb2760ab0b8315441 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    URL="https://github.com/truffleruby/truffleruby/releases/download/graal-${TRUFFLERUBY_VERSION}/truffleruby-community-${TRUFFLERUBY_VERSION}-linux-${TR_ARCH}.tar.gz"; \
    curl --fail --location --retry 3 --output /tmp/truffleruby.tar.gz "$URL"; \
    echo "${TR_SHA256}  /tmp/truffleruby.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/truffleruby; \
    tar -xzf /tmp/truffleruby.tar.gz --strip-components=1 -C /opt/truffleruby; \
    rm -f /tmp/truffleruby.tar.gz; \
    /opt/truffleruby/lib/truffle/post_install_hook.sh

ENV PATH=/opt/truffleruby/bin:${PATH}
WORKDIR /var/task

FROM truffleruby-base AS runtime

ENV BUNDLE_PATH=/opt/ores-ror-bundle \
    BUNDLE_GEMFILE=/var/task/Gemfile \
    BUNDLE_WITHOUT=rails:test \
    ORES_BUILD_TARGET=lambda \
    ORES_LAMBDA_HANDLER_GRANULARITY=route

COPY Gemfile ./Gemfile
RUN bundle install --jobs 4 --retry 3 \
    && truffleruby -e 'abort "rails gem present" unless Gem::Specification.find_all_by_name("rails").empty?' \
    && bundle exec truffleruby -e 'require "connection_pool"; require "base64"'

# The default build context is the application checkout. Runtime adapters are
# supplied from the separately named BuildKit context:
#   --build-context ores_infra=/path/to/ores-ror.infra
COPY . /var/task
COPY --from=ores_infra /aws-lambda/adapter.rb /var/task/aws-lambda/adapter.rb
COPY --from=ores_infra /aws-lambda/runtime.rb /var/task/aws-lambda/runtime.rb
COPY --from=ores_infra /aws-lambda/event-v2.json /var/task/aws-lambda/event-v2.json
COPY --from=ores_infra /aws-lambda/bootstrap /var/task/aws-lambda/bootstrap

RUN truffleruby bin/build-runtime \
    && test -f generated/lambda/manifest.json \
    && test -f generated/lambda/entrypoint.rb \
    && test -f 'generated/lambda/routes/users/[id]/_get/handler.rb' \
    && ! grep -R -E 'config/environment|Rails\.application' generated/lambda \
    && mkdir -p /var/runtime \
    && cp /var/task/aws-lambda/bootstrap /var/runtime/bootstrap \
    && chmod 0755 /var/runtime/bootstrap

ENTRYPOINT ["/var/runtime/bootstrap"]
