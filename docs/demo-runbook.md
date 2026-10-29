# FinSecure Pay Demo Runbook

## Goal

Demonstrate the working application, CI/CD and security checks, metrics dashboard, alert lifecycle, real email notification, and centralized logs. Use the local Docker Compose environment for the monitoring demo; do not start AWS EKS just for this runbook.

## Before the demo

1. Open Docker Desktop and ensure WSL integration is available.
2. Change to the repository root:

   ```bash
   cd /mnt/c/Users/qurix/Capstone_Assignment/finsecure-pay
   ```

3. Confirm local credentials/configuration are present without printing their contents:

   ```bash
   test -f .env.monitoring && echo '.env.monitoring exists' || echo 'Create .env.monitoring first'
   git check-ignore -v .env.monitoring
   git ls-files .env.monitoring
   ```

   The last command should print nothing.

4. Start the local monitoring stack:

   ```bash
   docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d
   docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
   ```

5. Check service health:

   ```bash
   curl -fsS http://localhost:8081/health
   curl -fsS http://localhost:3100/ready
   ```

6. Ensure the Prometheus targets used by this demo are healthy at `http://localhost:9090/targets` before triggering the alert.

## Suggested presentation sequence

### 1. Application and tests

Run:

```bash
./mvnw clean test
```

Show the test summary in the terminal or the JUnit report from Jenkins. Open `http://localhost:8081/health` to demonstrate the monitoring-profile app endpoint. For `/validate`, use the valid and invalid request bodies already documented in the source tests.

**Capture:** successful health response and passing test report.

### 2. Jenkins CI/CD

Open Jenkins at `http://localhost:8083`, open the `FinSecure-Pay-CI` job, and show a successful build with its stages and Console Output. Explain which stages build/test the application, run security checks, build/push the image, and execute the deployment workflow configured in the Jenkinsfile.

**Capture:** successful Pipeline stage view and the relevant console output. Do not display credentials or secret values.

### 3. Security checks

Show the Trivy/dependency and image scan stage outputs from the Jenkins build. Explain how the pipeline's configured security gate handles findings and cite the remediation already made for the critical dependency finding if that build is available. Avoid claiming the project is vulnerability-free; state the result of the specific scan/build shown.

**Capture:** scan results and the successful gated build after remediation.

### 4. Live monitoring dashboard

Open Grafana at `http://localhost:3000` and display the FinSecure Pay monitoring dashboard. Point out at least two meaningful metrics; preferably show application status, HTTP request rate, HTTP 5xx error rate, JVM heap usage, container CPU, and container memory. If a chart is empty, verify the Prometheus query and labels before presenting it.

Open Prometheus at `http://localhost:9090/targets` and demonstrate the application and cAdvisor targets are `UP`.

**Capture:** a complete dashboard view and the healthy target list.

### 5. Trigger and recover an alert

Before triggering, confirm `finsecure-pay-monitor` is running:

```bash
docker ps --filter name=finsecure-pay-monitor
```

Stop the application container deliberately:

```bash
docker stop finsecure-pay-monitor
```

Wait for the scrape/alert evaluation window configured in the project (the prior setup used frequent scrapes and a short pending period). Open `http://localhost:9090/alerts` and capture the application-down alert in the firing state. The exact time to firing depends on the current rule configuration.

Restore the app promptly:

```bash
docker start finsecure-pay-monitor
```

Wait for the target to return to `UP`, then verify that the alert resolves. Save a screenshot of both firing and recovery states. If stopping this container does not activate the configured rule, inspect `monitoring/` and use the rule's actual expression/threshold rather than inventing a different result.

**Capture:** alert configuration/rule, alert firing, and alert resolution.

### 6. Real Jenkins email notification

Show the received Jenkins notification email and the matching build number/result. If practical, show the notification-related Console Output. Avoid sharing SMTP credentials or message content unrelated to the assignment.

**Capture:** the actual received email and the Jenkins build result it refers to.

### 7. Centralized logs

Open Grafana → **Explore**, select the Loki data source, and set the time range to include recent application activity. Use the Label browser to choose the actual labels for the application container. A possible starting query, only if the corresponding label/value exists, is:

```logql
{container="finsecure-pay-monitor"}
```

Then filter for error-related entries, for example:

```logql
{container="finsecure-pay-monitor"} |~ "(?i)error|exception|failed"
```

Replace the label selector with the exact labels shown by the Label browser if your Alloy configuration uses different labels. Explain the troubleshooting flow: choose a time range, select a log stream, search for the incident/error, and correlate the timestamp with metrics and alert status.

**Capture:** Explore with query and real log entries visible.

## Evidence checklist

- [ ] Application health endpoint returns success.
- [ ] Maven/JUnit tests pass.
- [ ] Jenkins Pipeline stages and successful build.
- [ ] Security scan output and security-gate result.
- [ ] Grafana dashboard with live application/infrastructure metrics.
- [ ] Prometheus target health.
- [ ] Alert rule configuration.
- [ ] Alert firing screenshot.
- [ ] Alert recovery screenshot.
- [ ] Actual received Jenkins notification email.
- [ ] Grafana Explore showing Loki logs.
- [ ] Git status showing local secrets are not staged/tracked.

## After the demo

Confirm the application container has been restored and the alert has resolved. When no longer needed, stop the monitoring containers to free local resources while retaining named-volume data:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml down
```

Do not use `down -v` unless you intentionally want to delete stored metrics/logs.
