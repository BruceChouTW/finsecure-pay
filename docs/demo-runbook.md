# FinSecure Pay Demo Runbook

## Goal

Demonstrate the working application, CI/CD and security checks, metrics dashboard, alert lifecycle, real email notification, and centralized logs.

Use the local Docker Compose environment for the monitoring demo; do not start AWS EKS just for this runbook.

## Before the demo

### 1. Prepare the local environment

1. Start Docker Desktop.
2. Confirm Docker Desktop's WSL integration is enabled for the Ubuntu distribution used by this demo.
3. Open a WSL terminal and verify Docker Compose is available:

   ```bash
   docker version
   docker compose version
   ```

4. Make sure Git is installed and your GitHub SSH key is registered with the GitHub account that has access to the private repository. You can test SSH authentication with:

   ```bash
   ssh -T git@github.com
   ```

   GitHub may report that it does not provide shell access; that message is normal when SSH authentication succeeds.

### 2. Clone the project from GitHub

Start from a general workspace directory rather than a machine-specific project path:

```bash
mkdir -p ~/projects
cd ~/projects
git clone git@github.com:BruceChouTW/finsecure-pay.git
cd finsecure-pay
```

All remaining commands in this runbook assume the current directory is the repository root (`finsecure-pay`).

If you already cloned the repository, enter its root directory instead of cloning it again.

### 3. Create the local monitoring environment file

The file `.env.monitoring` is a **local Docker Compose environment-variable file**.

It supplies configuration values to `compose.monitoring.yaml`, including the Grafana administrator username and password.

It is intentionally excluded from Git because it contains local credentials. A fresh clone will not contain this file, so each environment must create its own copy.

#### 3.1 Check the required environment variable names

First, check which variable names the Compose file expects for Grafana:

```bash
grep -nE 'GF_SECURITY_ADMIN_USER|GF_SECURITY_ADMIN_PASSWORD|GRAFANA_' compose.monitoring.yaml
```

The variable names on the right-hand side of `${...}` must match the keys in `.env.monitoring`.

For example, if the Compose file maps:

```yaml
GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER}
GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
```

then the `.env.monitoring` file must use the following variable names:

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=replace-with-a-strong-unique-password
```

If `compose.monitoring.yaml` uses different interpolation variable names, use those exact names instead.

#### 3.2 Create `.env.monitoring`

Create the file:

```bash
umask 077
nano .env.monitoring
```

Add the following lines:

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=replace-with-a-strong-unique-password
```

Replace the example password with a strong, unique password for this local Grafana instance.

Save and close the editor, then restrict the file permissions:

```bash
chmod 600 .env.monitoring
```

The `GF_SECURITY_ADMIN_USER` and `GF_SECURITY_ADMIN_PASSWORD` settings are the Grafana container's administrator login. They are not the Jenkins SMTP credentials.

#### 3.3 Verify the environment file

Verify that the file exists, is ignored by Git, and that Compose can parse the configuration.

These commands do not print the password:

```bash
test -f .env.monitoring && echo '.env.monitoring exists'

git check-ignore -v .env.monitoring

git ls-files .env.monitoring

docker compose --env-file .env.monitoring -f compose.monitoring.yaml config --quiet
```

Expected results:

- `.env.monitoring exists` is printed.
- `git check-ignore` identifies the `.env.monitoring` ignore rule.
- `git ls-files .env.monitoring` prints nothing.
- `docker compose ... config --quiet` exits successfully without displaying a configuration error.

**Important security notes:**

- Do not commit `.env.monitoring`.
- Do not paste its contents into chat.
- Do not include its password in screenshots.
- Do not reuse production credentials for this local demo.

Open Grafana at:

```text
http://localhost:3000
```

Sign in with the username and password configured in `.env.monitoring`.

### 4. Start the local monitoring stack

From the repository root, run:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d

docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
```

Wait for the application, Prometheus, Grafana, cAdvisor, Alloy, and Loki containers to start.

A container showing `Up` does not always mean its service is already ready to accept requests.

### 5. Verify application and Loki readiness

#### 5.1 Check the application endpoint

Run:

```bash
curl -fsS http://localhost:8081/health
```

The application should return a successful health response.

#### 5.2 Check Loki readiness

Loki's `/ready` endpoint can temporarily return HTTP 503 while Loki is initializing.

For example:

```text
curl: (22) The requested URL returned error: 503
```

This does not necessarily mean Loki is broken. It may simply mean that Loki has not finished initializing its internal components and storage.

Do not treat one immediate 503 response as proof that Loki is broken.

Retry readiness for up to 150 seconds:

```bash
loki_ready=0

for attempt in $(seq 1 30); do
    if curl -fsS http://localhost:3100/ready >/dev/null 2>&1; then
        echo "Loki is ready"
        loki_ready=1
        break
    fi

    echo "Waiting for Loki readiness (${attempt}/30)..."
    sleep 5
done

if [ "$loki_ready" -ne 1 ]; then
    echo "Loki did not become ready. Recent Loki logs:"
    docker compose --env-file .env.monitoring -f compose.monitoring.yaml logs --tail=100 loki
    echo "Do not continue with the logging demo until Loki becomes ready."
fi
```

If the readiness check still fails after the retry period, inspect the Loki logs printed by the command.

In this project, a previously encountered cause was a permissions error when Loki tried to create `/tmp/loki/rules` in its data volume.

For example:

```text
mkdir /tmp/loki/rules: permission denied
```

If the logs specifically contain this error, repair the ownership of the existing Loki volume as follows.

#### 5.3 Repair Loki volume permissions if necessary

**Only perform this procedure if the Loki logs confirm a permission error involving `/tmp/loki/rules`.**

1. Confirm the Loki image's configured user and identify the named volume mounted at `/tmp/loki`:

   ```bash
   docker image inspect grafana/loki:3.7.0 --format 'Loki image user={{.Config.User}}'

   docker inspect finsecure-loki --format '{{range .Mounts}}{{if eq .Destination "/tmp/loki"}}{{.Name}}{{end}}{{end}}'
   ```

2. Stop only the Loki service and capture the volume name:

   ```bash
   docker compose --env-file .env.monitoring -f compose.monitoring.yaml stop loki

   LOKI_VOL=$(docker inspect finsecure-loki --format '{{range .Mounts}}{{if eq .Destination "/tmp/loki"}}{{.Name}}{{end}}{{end}}')

   echo "Loki volume: $LOKI_VOL"
   ```

3. If the volume name is non-empty and the image user is `10001` (the UID/GID used by the project image), repair directory ownership without deleting the stored data:

   ```bash
   if [ -z "$LOKI_VOL" ]; then
       echo "Could not identify the Loki data volume; check the Compose volume mapping."
   else
       docker run --rm --user 0 \
         -v "$LOKI_VOL:/data" \
         alpine:3.22 \
         sh -c 'mkdir -p /data/rules && chown -R 10001:10001 /data'
   fi
   ```

   If the image inspection reports a different numeric UID/GID, use that actual UID/GID in the `chown` command instead.

   Do not run the ownership repair unless you have confirmed the correct volume and the UID/GID from the image inspection.

4. Start Loki again:

   ```bash
   docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d loki
   ```

5. Repeat the readiness retry command from section 5.2.

**Important:** Do not remove the Loki volume or use `docker compose down -v` as a readiness workaround. That would delete stored Loki data.

If the logs show a different error, troubleshoot that error rather than applying the ownership repair blindly.

#### 5.4 Verify Prometheus targets

Once Loki is ready, open Prometheus:

```text
http://localhost:9090/targets
```

Confirm the `finsecure-pay` and `cadvisor` targets used by the dashboard are `UP`.

---

## Suggested presentation sequence

### 1. Application and tests

Run:

```bash
./mvnw clean test
```

Show the test summary in the terminal or the JUnit report from Jenkins.

Open:

```text
http://localhost:8081/health
```

Demonstrate the monitoring-profile app endpoint.

For `/validate`, use the valid and invalid request bodies already documented in the source tests.

**Capture:**

- Successful health response.
- Passing test report.

### 2. Jenkins CI/CD

Open Jenkins:

```text
http://localhost:8083
```

Open the `FinSecure-Pay-CI` job and show a successful build with its stages and Console Output.

Explain which stages:

- Build and test the application.
- Run security checks.
- Build and push the Docker image.
- Execute the deployment workflow configured in the Jenkinsfile.

**Capture:**

- Successful Pipeline stage view.
- Relevant Console Output.

Do not display credentials or secret values.

### 3. Security checks

Show the Trivy/dependency and image scan stage outputs from the Jenkins build.

Explain how the pipeline's configured security gate handles findings and cite the remediation already made for the critical dependency finding if that build is available.

Avoid claiming the project is vulnerability-free. State the result of the specific scan/build shown.

**Capture:**

- Security scan results.
- Successful gated build after remediation.

### 4. Live monitoring dashboard

Open Grafana:

```text
http://localhost:3000
```

Display the FinSecure Pay monitoring dashboard.

Sign in using the Grafana administrator credentials created in `.env.monitoring`.

Point out at least two meaningful metrics; preferably show:

- Application Status.
- HTTP Request Rate.
- HTTP 5xx Error Rate.
- JVM Heap Usage.
- Container CPU.
- Container Memory.

If a chart is empty, verify the Prometheus query and labels before presenting it.

Open Prometheus:

```text
http://localhost:9090/targets
```

Demonstrate the application and cAdvisor targets are `UP`.

**Capture:**

- Complete Grafana dashboard view.
- Healthy Prometheus target list.

### 5. Trigger and recover an alert

Before triggering, confirm `finsecure-pay-monitor` is running:

```bash
docker ps --filter name=finsecure-pay-monitor
```

Stop the application container deliberately:

```bash
docker stop finsecure-pay-monitor
```

Wait for the scrape/alert evaluation window configured in the project.

The prior setup used frequent scrapes and a short pending period.

Open:

```text
http://localhost:9090/alerts
```

Capture the application-down alert in the firing state.

Restore the app promptly:

```bash
docker start finsecure-pay-monitor
```

Wait for the target to return to `UP`, then verify that the alert resolves.

Save a screenshot of both firing and recovery states.

If stopping this container does not activate the configured rule, inspect `monitoring/` and use the rule's actual expression/threshold rather than inventing a different result.

**Capture:**

- Alert configuration/rule.
- Alert firing.
- Alert resolution.

### 6. Real Jenkins email notification

Show the received Jenkins notification email and the matching build number/result.

If practical, show the notification-related Console Output.

Avoid sharing SMTP credentials or message content unrelated to the assignment.

**Capture:**

- Actual received email.
- Matching Jenkins build result.

### 7. Centralized logs

Open Grafana → **Explore**.

Select the Loki data source and set the time range to include recent application activity.

Use the Label browser to choose the actual labels for the application container.

A possible starting query, only if the corresponding label/value exists, is:

```logql
{container="finsecure-pay-monitor"}
```

Then filter for error-related entries, for example:

```logql
{container="finsecure-pay-monitor"} |~ "(?i)error|exception|failed"
```

Replace the label selector with the exact labels shown by the Label browser if your Alloy configuration uses different labels.

Explain the troubleshooting flow:

1. Choose a time range.
2. Select a log stream.
3. Search for the incident/error.
4. Correlate the timestamp with metrics and alert status.

**Capture:**

- Grafana Explore with the query and real log entries visible.

---

## Evidence checklist

- [ ] GitHub clone from a clean workspace.
- [ ] `.env.monitoring` created locally and confirmed ignored by Git (do not capture its secret value).
- [ ] Application health endpoint returns success.
- [ ] Maven/JUnit tests pass.
- [ ] Jenkins Pipeline stages and successful build.
- [ ] Security scan output and security-gate result.
- [ ] Grafana dashboard with live application/infrastructure metrics.
- [ ] Prometheus target health.
- [ ] Loki readiness check succeeds and Grafana Explore shows application logs.
- [ ] Alert rule configuration.
- [ ] Alert firing screenshot.
- [ ] Alert recovery screenshot.
- [ ] Actual received Jenkins notification email.
- [ ] Git status showing local secrets are not staged/tracked.

## After the demo

Confirm the application container has been restored and the alert has resolved.

When no longer needed, stop the monitoring containers to free local resources while retaining named-volume data:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml down
```

Do not use `down -v` unless you intentionally want to delete stored metrics/logs.
