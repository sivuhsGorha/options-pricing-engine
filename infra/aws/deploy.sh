#!/bin/bash
# Deploys or updates the engine on the EC2 instance created by infra/aws/engine.yaml.
# Run on the instance as root:  sudo /opt/engine/infra/aws/deploy.sh
#
# What it does, in order:
#   1. pulls the configured branch of the repository into /opt/engine
#   2. writes .env and config.yaml from SSM Parameter Store (/<project>/env, /<project>/config)
#   3. on the very first start, seeds /app/data from s3://<transfer bucket>/data-seed/ if it exists
#   4. docker compose up --build -d, then waits for the container to report healthy
set -euo pipefail

CONF=/etc/options-engine.conf
[ -f "$CONF" ] || { echo "missing $CONF (was the instance created by engine.yaml?)" >&2; exit 1; }
# shellcheck disable=SC1090
source "$CONF"
export AWS_DEFAULT_REGION="$AWS_REGION"

APP_DIR=/opt/engine
SEED_MARK="$APP_DIR/.data-seeded"
cd "$APP_DIR"

echo "== 1/4 code: $REPO_URL @ $REPO_REF"
git fetch --quiet origin "$REPO_REF"
git checkout --quiet "$REPO_REF"
git reset --quiet --hard "origin/$REPO_REF"
git log -1 --oneline

echo "== 2/4 configuration from Parameter Store (/$PROJECT_NAME/env, /$PROJECT_NAME/config)"
umask 077
aws ssm get-parameter --name "/$PROJECT_NAME/env" --with-decryption \
    --query Parameter.Value --output text > .env
aws ssm get-parameter --name "/$PROJECT_NAME/config" \
    --query Parameter.Value --output text > config.yaml
umask 022
chmod 644 config.yaml   # the container reads it as a non-root user; it holds no secrets
# Sanity check without starting the engine: exits 1 and lists every problem if something is wrong.
# (Runs in a throwaway container so the host needs no JDK.)
if docker image inspect options-pricing-engine:local >/dev/null 2>&1; then
    docker run --rm --env-file .env -v "$PWD/config.yaml:/app/config.yaml:ro" \
        --entrypoint java options-pricing-engine:local -jar /app/app.jar --check-config || {
        echo "configuration rejected; fix the parameters and rerun" >&2; exit 1; }
fi

echo "== 3/4 data"
if [ ! -f "$SEED_MARK" ]; then
    if aws s3 ls "s3://$TRANSFER_BUCKET/data-seed/" >/dev/null 2>&1; then
        echo "seeding /app/data from s3://$TRANSFER_BUCKET/data-seed/"
        rm -rf /tmp/data-seed && mkdir -p /tmp/data-seed
        aws s3 cp "s3://$TRANSFER_BUCKET/data-seed/" /tmp/data-seed/ --recursive --quiet
        docker compose build --quiet
        docker compose create
        CID=$(docker compose ps -aq options-engine)
        docker cp /tmp/data-seed/. "$CID:/app/data/"
        # the engine runs as appuser inside the container and must be able to write the ledger
        docker run --rm -u root -v "$(basename "$PWD")_engine-data:/app/data" --entrypoint sh \
            options-pricing-engine:local -c "chown -R appuser:appgroup /app/data"
        rm -rf /tmp/data-seed
    else
        echo "no data-seed in the transfer bucket: the ledger starts empty"
    fi
    touch "$SEED_MARK"
else
    echo "already seeded; the engine-data volume is kept across deploys"
fi

echo "== 4/4 start"
docker compose up --build -d
for i in $(seq 1 60); do
    status=$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q options-engine)" 2>/dev/null || echo starting)
    [ "$status" = "healthy" ] && break
    sleep 2
done
echo "container health: $status"
docker compose logs --tail 40 options-engine
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/health || true)
echo "unauthenticated /api/health -> HTTP $code (401 means the server is up and refusing anonymous requests)"
[ "$status" = "healthy" ] && [ "$code" = "401" ]
