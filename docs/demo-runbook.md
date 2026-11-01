# FinSecure Pay Demo Runbook

## Goal

Demonstrate the working application, CI/CD and security checks, the earlier validated AWS EKS deployment, metrics dashboard, alert lifecycle, real email notification, and centralized logs.

Use the local Docker Compose environment for the monitoring demo. The EKS deployment is a separate cloud deployment workflow and does not need to be started again for the final local monitoring demo. This keeps the demonstration cost-controlled.

## Before the demo

### 1. Prepare the local environment

1. Start Docker Desktop.
2. Confirm Docker Desktop's WSL integration is enabled for the Ubuntu distribution used by this demo.
3. Open a WSL terminal and verify Docker Compose is available:

   ```bash
   docker version
   docker compose version
   ```

4. Make sure Git is installed and your GitHub SSH key is registered with the GitHub account that has access to the private repository. Test SSH authentication:

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

All remaining local-demo commands assume the current directory is the repository root (`finsecure-pay`).

If you already cloned the repository, enter its root directory instead of cloning it again.

### 3. Create the local monitoring environment file

The file `.env.monitoring` is a local Docker Compose environment-variable file. It supplies configuration values to `compose.monitoring.yaml`, including the Grafana administrator username and password.

It is intentionally excluded from Git because it contains local credentials. A fresh clone will not contain this file, so each environment must create its own copy.

#### 3.1 Check the required environment variable names

```bash
grep -nE 'GF_SECURITY_ADMIN_USER|GF_SECURITY_ADMIN_PASSWORD|GRAFANA_' compose.monitoring.yaml
```

The variable names on the right-hand side of `${...}` must match the keys in `.env.monitoring`.

For example, if the Compose file maps:

```yaml
GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER}
GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
```

then `.env.monitoring` must use:

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=replace-with-a-strong-unique-password
```

If `compose.monitoring.yaml` uses different interpolation variable names, use those exact names instead.

#### 3.2 Create `.env.monitoring`

```bash
umask 077
nano .env.monitoring
```

Add the appropriate variable names and a strong, unique password for this local Grafana instance. Save and close the editor, then restrict the file permissions:

```bash
chmod 600 .env.monitoring
```

The `GF_SECURITY_ADMIN_USER` and `GF_SECURITY_ADMIN_PASSWORD` settings are the Grafana container's administrator login. They are not the Jenkins SMTP credentials.

#### 3.3 Verify the environment file

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

**Security notes:**

- Do not commit `.env.monitoring`.
- Do not paste its contents into chat.
- Do not include its password in screenshots.
- Do not reuse production credentials for this local demo.

Open Grafana at `http://localhost:3000` and sign in with the credentials configured in `.env.monitoring`.

### 4. Start the local monitoring stack

From the repository root:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d
docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
```

Wait for the application, Prometheus, Grafana, cAdvisor, Alloy, and Loki containers to start.

A container showing `Up` does not always mean its service is already ready to accept requests.

### 5. Verify application and Loki readiness

#### 5.1 Check the application endpoint

```bash
curl -fsS http://localhost:8081/health
```

The application should return a successful health response.

#### 5.2 Check Loki readiness

Loki's `/ready` endpoint can temporarily return HTTP 503 while Loki is initializing. One immediate 503 response does not necessarily mean Loki is broken.

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

If readiness still fails, inspect the Loki logs. A previously encountered project issue was a permissions error when Loki tried to create `/tmp/loki/rules` in its data volume, for example:

```text
mkdir /tmp/loki/rules: permission denied
```

#### 5.3 Repair Loki volume permissions only if the logs confirm that specific error

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

3. If the volume name is non-empty and image inspection confirms UID/GID `10001`, repair ownership without deleting stored data:

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

   If image inspection reports a different numeric UID/GID, use that actual UID/GID instead. Do not run the repair unless you have confirmed the correct volume and UID/GID.

4. Start Loki again:

   ```bash
   docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d loki
   ```

5. Repeat the readiness retry from section 5.2.

Do not remove the Loki volume or use `docker compose down -v` as a readiness workaround; that would delete stored Loki data. If the logs show a different error, troubleshoot that error rather than applying this ownership repair blindly.

#### 5.4 Verify Prometheus targets

Open `http://localhost:9090/targets` and confirm the `finsecure-pay` and `cadvisor` targets used by the dashboard are `UP`.

---

## AWS EKS deployment workflow (Task 3)

This section records the cloud deployment workflow and its validation. It is separate from the local Docker Compose observability demo.

### 1. EKS environment details

The Task 3 environment used:

| Item | Value |
|---|---|
| AWS Region | `us-east-1` |
| EKS Cluster | `finsecure-capstone` |
| Kubernetes version | `1.37` |
| Managed node group name | `finsecure-workers` |
| Provisioning tool | `eksctl` |
| Kubernetes CLI | `kubectl` |
| Worker compute | Amazon EC2 |

The EKS control plane was observed in `ACTIVE` state. An `ACTIVE` control plane alone does not prove that a worker node group is available or that application Pods are running.

### 2. Check AWS and Kubernetes access

Run these checks when the EKS environment is intended to be used:

```bash
aws sts get-caller-identity
aws eks describe-cluster \
  --region us-east-1 \
  --name finsecure-capstone
```

The cluster description should be checked for its status and Kubernetes version. The recorded cluster status was `ACTIVE` and its Kubernetes version was `1.37`.

Configure or refresh the local kubeconfig context:

```bash
aws eks update-kubeconfig \
  --region us-east-1 \
  --name finsecure-capstone
```

Check the cluster and worker nodes:

```bash
kubectl cluster-info
kubectl get nodes -o wide
```

If `kubectl get nodes` returns no nodes, or the node group is absent, do not proceed as if the application deployment is currently available. Check the managed node groups and their status:

```bash
aws eks list-nodegroups \
  --region us-east-1 \
  --cluster-name finsecure-capstone
```

### 3. Locate and apply the Kubernetes manifests

The Task 3 evidence includes Kubernetes manifests for the Deployment, Service, and ConfigMap. Locate the actual files in the repository rather than assuming a particular directory:

```bash
find . -type f \( \
  -name 'deployment.yaml' -o \
  -name 'service.yaml' -o \
  -name 'configmap.yaml' \
\)
```

Review the manifests before applying them. Confirm the image reference, namespace, ports, replica count, configuration values, and probe paths match the intended deployment.

Apply the actual manifest files from their discovered paths. For example, after changing to the directory containing the three files:

```bash
kubectl apply -f configmap.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
```

Wait for the Deployment rollout:

```bash
kubectl rollout status deployment/<deployment-name>
```

Replace `<deployment-name>` with the actual Kubernetes Deployment name from the manifest.

### 4. Validate the deployment

Inspect the Deployment, Pods, Service, and nodes:

```bash
kubectl get deployments
kubectl get pods -o wide
kubectl get services
kubectl get nodes -o wide
```

The Task 3 validation evidence demonstrated:

- Two application replicas.
- Running application Pods.
- Kubernetes Service configuration and external application access.
- ConfigMap-based application configuration.
- Readiness and liveness probe configuration.
- Automatic Pod recovery after a running Pod was deleted.

To demonstrate recovery in a live cluster, first identify an application Pod and confirm the Deployment has the intended replica count. Then delete one Pod:

```bash
kubectl delete pod <pod-name>
kubectl get pods -w
```

Kubernetes Deployment reconciliation should create a replacement Pod to restore the desired replica count. Replace `<pod-name>` with an actual Pod name. Do not run this test against an environment that is not intended for disruption.

For an externally exposed Service, use the actual Service type and endpoint shown by `kubectl get services`. Verify the application endpoint using the address and port assigned to that Service. Do not assume a fixed external URL or port.

### 5. EKS provisioning history and cost considerations

The project has evidence of an earlier successful Task 3 deployment and validation, including two replicas, running Pods, external access, probe configuration, and automatic Pod recovery.

Subsequent attempts to recreate the managed node group encountered provisioning issues:

- The CloudFormation managed node group stack `eksctl-finsecure-capstone-nodegroup-finsecure-workers` failed with an `AsgInstanceLaunchFailures` error because the selected On-Demand instance type was not eligible for the Free Tier.
- A later node group creation attempt timed out, and the node group was subsequently not listed.
- The EKS control plane could still report `ACTIVE` even when the worker node group was unavailable.

These later provisioning attempts must not be described as successful. When presenting Task 3, use the saved evidence from the earlier successful deployment. If attempting to reproduce the deployment, first verify the current node group and worker-node status, then choose an EC2 instance type and capacity that are eligible for the account and acceptable for the project budget.

**Cost control:** EKS control plane and EC2 worker resources may incur charges. Do not leave resources running solely for the local monitoring demonstration. Check the current AWS resource state and billing before and after any cloud deployment activity. Do not assume the cluster or node group has been deleted; verify the actual AWS state before claiming cleanup is complete.

---

## Suggested presentation sequence

### 1. Application and tests

Run:

```bash
./mvnw clean test
```

Show the test summary in the terminal or the JUnit report from Jenkins.

Open `http://localhost:8081/health` to demonstrate the monitoring-profile app endpoint.

For `/validate`, use the valid and invalid request bodies already documented in the source tests.

**Capture:**
- Successful health response.
- Passing test report.

### 2. Docker and Kubernetes deployment evidence

Show the Docker image version and the Docker Hub repository. Then present the saved Task 3 EKS evidence from the earlier successful deployment:

- Two-replica Deployment configuration.
- Running Pods.
- Service configuration and external application access.
- ConfigMap.
- Readiness/liveness probes.
- Automatic Pod recovery.

Do not start EKS solely to demonstrate the local monitoring stack. If you choose to run a live EKS demo, complete the checks in the EKS section first and verify that worker nodes are ready.

**Capture:** Docker image/registry evidence and the relevant saved EKS deployment screenshots or command outputs.

### 3. Jenkins CI/CD

Open Jenkins at `http://localhost:8083`, open the `FinSecure-Pay-CI` job, and show a successful build with its stages and Console Output.

Explain which stages:
- Build and test the application.
- Run security checks.
- Build and push the Docker image.
- Execute the deployment workflow configured in the Jenkinsfile.

**Capture:**
- Successful Pipeline stage view.
- Relevant Console Output.

Do not display credentials or secret values.

### 4. Security checks

Show the Trivy/dependency and image scan stage outputs from the Jenkins build.

Explain how the pipeline's configured security gate handles findings and cite the remediation already made for the critical dependency finding if that build is available.

Avoid claiming the project is vulnerability-free. State the result of the specific scan/build shown.

**Capture:**
- Security scan results.
- Successful gated build after remediation.

### 5. Live monitoring dashboard

Open Grafana at `http://localhost:3000` and display the FinSecure Pay monitoring dashboard.

Sign in using the Grafana administrator credentials created in `.env.monitoring`.

Point out at least two meaningful metrics; preferably show:
- Application Status.
- HTTP Request Rate.
- HTTP 5xx Error Rate.
- JVM Heap Usage.
- Container CPU.
- Container Memory.

If a chart is empty, verify the Prometheus query and labels before presenting it.

Open Prometheus at `http://localhost:9090/targets` and demonstrate the application and cAdvisor targets are `UP`.

**Capture:**
- Complete Grafana dashboard view.
- Healthy Prometheus target list.

### 6. Trigger and recover an alert

Before triggering, confirm `finsecure-pay-monitor` is running:

```bash
docker ps --filter name=finsecure-pay-monitor
```

Stop the application container deliberately:

```bash
docker stop finsecure-pay-monitor
```

Wait for the scrape/alert evaluation window configured in the project. The prior setup used frequent scrapes and a short pending period.

Open `http://localhost:9090/alerts` and capture the application-down alert in the firing state.

Restore the app promptly:

```bash
docker start finsecure-pay-monitor
```

Wait for the target to return to `UP`, then verify that the alert resolves.

Save a screenshot of both firing and recovery states. If stopping this container does not activate the configured rule, inspect `monitoring/` and use the rule's actual expression/threshold rather than inventing a different result.

**Capture:**
- Alert configuration/rule.
- Alert firing.
- Alert resolution.

### 7. Real Jenkins email notification

Show the received Jenkins notification email and the matching build number/result.

If practical, show the notification-related Console Output.

Avoid sharing SMTP credentials or message content unrelated to the assignment.

**Capture:**
- Actual received email.
- Matching Jenkins build result.

### 8. Centralized logs

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

**Capture:** Grafana Explore with the query and real log entries visible.

---

## Evidence checklist

### Application and containerization
- [ ] GitHub clone from a clean workspace.
- [ ] `.env.monitoring` created locally and confirmed ignored by Git (do not capture its secret value).
- [ ] Application health endpoint returns success.
- [ ] Maven/JUnit tests pass.
- [ ] Docker image build, version, and registry evidence.
- [ ] Container execution and health endpoint evidence.

### AWS EKS / Kubernetes (Task 3)
- [ ] EKS cluster details showing the intended cluster, region, status, and Kubernetes version.
- [ ] Worker-node status checked; do not infer node availability from control-plane status alone.
- [ ] Deployment manifest and two-replica configuration.
- [ ] Running Pods evidence.
- [ ] Service configuration and external application access.
- [ ] ConfigMap evidence.
- [ ] Readiness and liveness probe configuration.
- [ ] Automatic Pod recovery evidence.
- [ ] EKS provisioning failures and cost considerations accurately distinguished from the earlier successful deployment.

### CI/CD and security
- [ ] Jenkins Pipeline stages and successful build.
- [ ] Security scan output and security-gate result.
- [ ] Remediation evidence, where applicable.
- [ ] Actual received Jenkins notification email.

### Monitoring and logging
- [ ] Grafana dashboard with live application/infrastructure metrics.
- [ ] Prometheus target health.
- [ ] Loki readiness check succeeds and Grafana Explore shows application logs.
- [ ] Alert rule configuration.
- [ ] Alert firing screenshot.
- [ ] Alert recovery screenshot.
- [ ] Git status showing local secrets are not staged/tracked.

## After the demo

Confirm the application container has been restored and the alert has resolved.

When no longer needed, stop the local monitoring containers to free local resources while retaining named-volume data:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml down
```

Do not use `down -v` unless you intentionally want to delete stored metrics/logs.

For AWS, independently verify the current EKS cluster, node groups, EC2 instances, and related resources before deciding whether to stop or delete them. Confirm the resulting AWS state and costs; do not assume cleanup has completed without verification.
