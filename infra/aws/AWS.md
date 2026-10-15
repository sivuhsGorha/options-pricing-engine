# Running on AWS (infra/aws)

One EC2 instance (Ubuntu 24.04, t3.small) running the same `docker-compose.yml` as a laptop, created by
the CloudFormation template [engine.yaml](engine.yaml). Nothing is reachable from the internet: the
security group has no inbound rule, and the dashboard is reached through an SSM port-forwarding session.
Secrets live in SSM Parameter Store and are written to the instance by [deploy.sh](deploy.sh).

Cost in us-east-1, running 24/7: about US$15/month for the instance and US$2 for the 20 GB disk. A
stopped instance costs only the disk.

## Once: tools on your PC

1. AWS CLI v2 configured for the account (`aws sts get-caller-identity` prints your account id).
2. The Session Manager plugin, which the CLI needs for `start-session`:
   https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html

All commands below run from the repository root. `REGION` is the region you deploy to (`us-east-1`).

## 1. Store the configuration (before creating the stack)

The instance reads two parameters at deploy time. `.env` holds secrets, so it is a `SecureString`;
`config.yaml` is not secret. Both files stay git-ignored on your PC.

```bash
aws ssm put-parameter --region us-east-1 --name /options-engine/env    --type SecureString --value file://.env
aws ssm put-parameter --region us-east-1 --name /options-engine/config --type String       --value file://config.yaml
```

Rotate a secret later with the same command plus `--overwrite`, then rerun `deploy.sh` on the instance.

`.env` needs no AWS-specific edits: `docker-compose.yml` already overrides `BIND_ADDRESS`, `DATA_DIR` and
`MMAP_STATE_FILE` for the container, and the dashboard is only ever reached as `http://127.0.0.1:8080`
through the tunnel, so `ALLOWED_ORIGIN` and `COOKIE_SECURE` keep their local values.

## 2. Create the stack

```bash
aws cloudformation deploy --region us-east-1 --stack-name options-engine \
  --template-file infra/aws/engine.yaml --capabilities CAPABILITY_IAM

aws cloudformation describe-stacks --region us-east-1 --stack-name options-engine \
  --query "Stacks[0].Outputs" --output table
```

Takes about three minutes. The outputs give the instance id, the transfer bucket, and the two commands
you will use every day (shell and tunnel). The instance's first boot installs Docker, the AWS CLI and a
swap file and clones the repository (`/var/log/engine-bootstrap.log` on the instance); it does not start
the engine.

## 3. Seed the ledger (optional, before the first deploy)

With `execution.transport: alpaca`, the Alpaca paper account must agree with the local book, or
reconciliation halts trading at the first start ([RUNBOOK.md](../../RUNBOOK.md), "Hosting on a server").
Either flatten the paper account first, or upload the existing `data/` and `deploy.sh` copies it into the
container's volume on the first start only:

```bash
aws s3 cp data/ s3://<TransferBucket output>/data-seed/ --recursive --exclude "*.dat" --exclude "historical/*"
```

## 4. Deploy (first time and every update)

```bash
aws ssm start-session --region us-east-1 --target <InstanceId output>
```

then, on the instance:

```bash
sudo /opt/engine/infra/aws/deploy.sh
```

The first run builds the frontend and the jar on the instance (5 to 10 minutes on a t3.small; later runs
reuse the Docker cache). The script ends by printing the container's health and the last 40 log lines;
look for `Options Trading Dashboard Live at:` and, with Alpaca, `[RECONCILE] OK`. It exits non-zero if the
container is not healthy or `/api/health` does not answer 401 to an anonymous request.

To update after a push to the branch: same two commands. The ledger volume is kept.

## 5. Use the dashboard

In a terminal on your PC, leave this running:

```bash
aws ssm start-session --region us-east-1 --target <InstanceId> \
  --document-name AWS-StartPortForwardingSession --parameters file://infra/aws/tunnel.json
```

and open http://127.0.0.1:8080. Sign in with `OPERATOR_PASSWORD`. Close the terminal to close the tunnel.

## Day to day

| Task | Command (on the instance, after `start-session`) |
| :--- | :--- |
| Engine log | `cd /opt/engine && docker compose logs -f --tail 200` |
| Restart | `cd /opt/engine && docker compose restart` |
| Stop the engine, keep the instance | `cd /opt/engine && docker compose down` (ledger stays in the volume) |
| Health from the box | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/health` (401 = up) |
| Back up the ledger | `cd /opt/engine && docker compose cp options-engine:/app/data ./data-backup` then `aws s3 cp data-backup s3://<bucket>/backups/$(date +%F)/ --recursive` |

From your PC:

| Task | Command |
| :--- | :--- |
| Stop paying for compute | `aws ec2 stop-instances --region us-east-1 --instance-ids <id>` (`start-instances` to resume; Docker restarts the container on boot) |
| Resize | `aws cloudformation deploy ... --parameter-overrides InstanceType=t3.medium` (the instance is replaced; back up the ledger first) |
| Tear everything down | empty the bucket, then delete the stack: `aws s3 rm s3://<bucket> --recursive && aws cloudformation delete-stack --region us-east-1 --stack-name options-engine` |

## What is deliberately not here yet

- Automatic deploys on push (a GitHub Actions job that runs `deploy.sh` through SSM). Next step.
- Public HTTPS on a subdomain. Possible later with Caddy in front; until then the tunnel is the only way in.
- Monitoring and alarms. Set a billing alert in the AWS console (Billing > Budgets) before leaving it running.
