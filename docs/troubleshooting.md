# Troubleshooting Guide

## Find the repository root

```bash
cd /mnt/c/Users/qurix/Capstone_Assignment/finsecure-pay
```

## Application health or metrics endpoint is unavailable

Check container status first:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
```

Inspect the application logs:

```bash
docker logs --tail=100 finsecure-pay-monitor
```

Probe the endpoints:

```bash
curl -i http://localhost:8081/health
curl -i http://localhost:8081/actuator/health
curl -fsS http://localhost:8081/actuator/prometheus | head -n 30
```

If the metrics output is empty or unexpected, check the monitoring profile/configuration and confirm that requests have been generated. Use the existing application configuration and tests as the source of truth for endpoint behavior.

## Prometheus dashboard panel has no data

1. Open `http://localhost:9090/targets` and verify the relevant target is `UP`.
2. Query the metric directly in Prometheus before changing Grafana display options.
3. Inspect actual metric labels. cAdvisor versions/configurations may expose container identity through labels such as `id`; do not assume a `name` label exists.
4. For container metrics, start with a broad query such as:

   ```promql
   sum(rate(container_cpu_usage_seconds_total{id=~"/docker/.*"}[1m]))
   ```

   ```promql
   sum(container_memory_working_set_bytes{id=~"/docker/.*"})
   ```

5. In Grafana, use the Query **Legend** setting to change the displayed series name. Use **Standard options → Unit** for the unit and avoid setting inappropriate Min/Max values that could make valid data hard to see.

For the application 5xx panel, a query may legitimately return no matching error series when no 5xx requests have occurred. If the panel should show zero in that case, use the configured query pattern with `or vector(0)` and first confirm the base application metric exists.

## Loki is not ready or Grafana Explore shows no logs

Check Loki readiness:

```bash
curl -i http://localhost:3100/ready
```

Check the relevant containers:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
docker logs --tail=100 finsecure-loki
docker logs --tail=100 finsecure-alloy
```

Then check Grafana Explore:

1. Select the Loki data source.
2. Set a time range covering recent app activity.
3. Use the Label browser to inspect available labels and values.
4. Build the selector from those actual labels; do not assume `container="finsecure-pay-monitor"` exists unless the label browser confirms it.
5. If Loki fails at startup due to a volume permission error, use the actual mounted volume and the container UID/GID to fix ownership; do not delete the volume as the first response.

## Prometheus alert does not fire

1. Open `http://localhost:9090/alerts` and inspect the rule's state and expression.
2. Confirm the required target was `UP` before performing the test.
3. Check the rule's `for` duration and wait through the configured pending period plus a scrape/evaluation interval.
4. Inspect `monitoring/` to confirm the configured alert name and labels.
5. Restore the stopped container after capturing the firing state and confirm the alert returns to normal.

## Jenkins cannot access Docker daemon

The common symptom is `permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock`. From WSL, inspect the container and test Docker access as the Jenkins user:

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
docker exec -u jenkins finsecure-jenkins sh -lc 'id; ls -ln /var/run/docker.sock; docker info >/dev/null && echo OK'
```

Ensure the socket is mounted and the Jenkins container's supplementary group includes the socket's group ID. Keep the current working `group_add` / `DOCKER_GID` configuration. Do not make the socket world-writable with `chmod 666`; access to Docker is highly privileged.

## Jenkins email notification fails

- Confirm the Email Extension Plugin is installed.
- Check **Manage Jenkins → System → Extended E-mail Notification** for SMTP host, port, TLS setting, and credentials.
- Check the Jenkins Console Output for SMTP authentication, TLS, or connection errors.
- Check the recipient address and spam folder.
- Keep SMTP passwords in Jenkins Credentials or a secure settings store, not in `Jenkinsfile` or Git.

## Git hygiene before commit

```bash
git status --short
git diff --check
git check-ignore -v .env.monitoring
git ls-files .env.monitoring
```

The secret environment file should be ignored and untracked. Review the staged file list before committing; do not add credentials, `.env.monitoring`, Jenkins home data, Docker socket files, or runtime data.
