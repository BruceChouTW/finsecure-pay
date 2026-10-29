# FinSecure Pay

FinSecure Pay is a sample payment-validation service used to demonstrate an end-to-end DevOps and DevSecOps workflow. The project combines a Spring Boot application and automated tests with containerization, Jenkins CI/CD, vulnerability scanning, local monitoring, centralized logging, alerting, and email notifications.

> **Environment note:** The local Docker Compose monitoring stack is used for the final monitoring demo. The AWS EKS environment was used and validated earlier, but is intentionally not required to be running for this demo to avoid unnecessary cloud charges.

## Contents

- [Architecture](docs/architecture.md)
- [Demo runbook](docs/demo-runbook.md)
- [Troubleshooting](docs/troubleshooting.md)

## Technology stack

| Area | Technology | Purpose |
|---|---|---|
| Application | Java, Spring Boot, Maven | REST API and automated tests |
| Containerization | Docker | Build and run the application image |
| CI/CD | Jenkins, Jenkinsfile | Automate build, test, security checks, image publishing, and deployment steps configured by the pipeline |
| Security | Trivy and pipeline security gates | Scan dependencies/filesystem and container images; block builds when configured policy is violated |
| Runtime/deployment | Kubernetes / Amazon EKS (validated earlier) | Demonstrate deployment, health checks, and recovery behavior; not required to be active for the local demo |
| Metrics | Spring Boot Actuator, Prometheus, cAdvisor | Collect application and container metrics |
| Visualization and alerting | Grafana, Prometheus alert rules | Show telemetry and demonstrate an alert lifecycle |
| Centralized logging | Grafana Alloy, Loki, Grafana Explore | Forward, store, and query application logs |
| Notification | Jenkins Email Extension Plugin, SMTP | Notify the recipient of Pipeline build results |

## Repository layout

```text
finsecure-pay/
├── Jenkinsfile
├── compose.yaml                         # Jenkins service and build configuration
├── jenkins/                             # Jenkins image build context
├── Dockerfile                           # FinSecure Pay application image
├── compose.monitoring.yaml              # Local app + monitoring/logging stack
├── .env.monitoring                      # Local configuration/secrets; ignored by Git
├── monitoring/                          # Prometheus, Alloy, Loki, and alert configuration
├── pom.xml
├── mvnw
├── src/
└── docs/
    ├── architecture.md
    ├── demo-runbook.md
    └── troubleshooting.md
```

The exact contents under `monitoring/` and `src/` are defined by the repository files. Do not commit local credentials or generated runtime data.

## Application API

The service exposes the following endpoints used in the project tests and monitoring setup:

| Endpoint | Purpose |
|---|---|
| `GET /health` | Application health endpoint |
| `/validate` | Payment validation endpoint; use the valid and invalid request examples covered by the project tests |
| `GET /actuator/health` | Spring Boot Actuator health information when the monitoring configuration is enabled |
| `GET /actuator/prometheus` | Prometheus-format application metrics when the monitoring configuration is enabled |

For the standard Spring Boot local run, the application typically listens on port `8080` unless configuration overrides it:

```bash
./mvnw clean test
./mvnw package
./mvnw spring-boot:run
```

In a separate terminal, verify the health endpoint:

```bash
curl -i http://localhost:8080/health
```

Use the request payloads from the existing tests when verifying `/validate`; this README intentionally does not invent a request schema.

## Run the local monitoring and logging stack

Prerequisites: Docker Desktop running with WSL integration enabled, and a local `.env.monitoring` file created according to the repository's existing configuration. Do not commit the environment file.

From the repository root:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml up -d
docker compose --env-file .env.monitoring -f compose.monitoring.yaml ps
```

Expected local endpoints for the current monitoring configuration:

| Service | URL | Use |
|---|---|---|
| FinSecure Pay (monitoring profile) | `http://localhost:8081/health` | Application health |
| Actuator health | `http://localhost:8081/actuator/health` | Application health details |
| Actuator Prometheus metrics | `http://localhost:8081/actuator/prometheus` | Inspect exported metrics |
| Prometheus | `http://localhost:9090` | Query metrics, inspect targets, and view alerts |
| Grafana | `http://localhost:3000` | Dashboard, Explore, and log queries |
| cAdvisor | `http://localhost:8082` | Container metrics source |
| Loki readiness | `http://localhost:3100/ready` | Confirm Loki is ready |
| Alloy UI | `http://localhost:12345` | Inspect log collection/forwarding status |

Use the Grafana credentials from your local environment configuration. Do not place usernames/passwords in this README or in source control.

Useful checks:

```bash
curl -i http://localhost:8081/health
curl -i http://localhost:8081/actuator/health
curl -fsS http://localhost:8081/actuator/prometheus | head
curl -i http://localhost:3100/ready
```

### Dashboard metrics

The Grafana dashboard should provide meaningful live application and infrastructure metrics, including:

- Application availability (`up{job="finsecure-pay"}`)
- HTTP request rate
- HTTP 5xx error rate
- JVM heap usage
- Container CPU usage
- Container memory usage

The exact queries and labels depend on the Prometheus scrape and cAdvisor configuration in this repository. If a query returns no data, check Prometheus **Status → Targets**, then inspect available labels before assuming a specific label name.

### Centralized logs

In Grafana, open **Explore**, select the Loki data source, set a suitable time range, and use the label browser to select a log stream. For example, if the collected stream has the corresponding label, a query may look like:

```logql
{container="finsecure-pay-monitor"}
```

Label names can vary with Alloy configuration. Use the labels shown by Grafana's label browser rather than assuming the example label exists. For troubleshooting details, see [Troubleshooting](docs/troubleshooting.md).

### Alert demonstration

Use the existing Prometheus alert rule in `monitoring/` and follow the controlled alert/recovery steps in [Demo runbook](docs/demo-runbook.md). Capture evidence of both the firing state and recovery state. Restore any stopped container immediately after the test.

To stop the local monitoring stack while preserving named-volume data:

```bash
docker compose --env-file .env.monitoring -f compose.monitoring.yaml down
```

Do **not** add `-v` unless you intentionally want to delete the stored metrics/logs in named volumes.

## Jenkins CI/CD

`compose.yaml` is the Compose file for the Jenkins service. Its build context is `./jenkins`, so run Jenkins Compose commands from the repository root:

```bash
docker compose build jenkins
docker compose up -d jenkins
docker compose ps
```

The Jenkins UI is bound to `127.0.0.1:8083` in the current configuration. The Docker socket is mounted so Pipeline stages can build and run Docker images; this grants Jenkins significant control over the Docker host. Keep Jenkins restricted to the local development environment and do not expose it publicly.

The Jenkins Pipeline includes the project's build/test and configured security/image workflow. It also sends a build-result email through the Email Extension Plugin and the SMTP settings configured in Jenkins. SMTP passwords and other credentials must be stored in Jenkins Credentials or local secret configuration, never in `Jenkinsfile`.

After editing the pipeline, validate the repository changes and inspect the build result in Jenkins. Do not include Jenkins home data, SMTP credentials, Docker Hub tokens, AWS credentials, or `.env.monitoring` in Git.

## Security and configuration notes

- `.env.monitoring` is ignored by Git and should remain untracked.
- Store SMTP and registry credentials in Jenkins Credentials; do not hardcode secrets in the repository.
- Review `git status --short` and the staged file list before each commit.
- Trivy scan results should be reviewed and remediated according to the pipeline's configured policy; do not describe a scan as clean unless the relevant scan actually passed.
- The Docker socket mount is powerful. Keep the Jenkins service local and access-controlled.
- AWS EKS was used for deployment validation earlier. It does not need to be running to use the local monitoring stack or present the final demo.

## Verification checklist

- [ ] `./mvnw clean test` passes.
- [ ] Application health endpoint returns success.
- [ ] Jenkins Pipeline completes and the console output is available.
- [ ] Security scan stages and their results are captured.
- [ ] Grafana dashboard displays application and container metrics.
- [ ] Prometheus targets are healthy before testing the alert.
- [ ] Alert firing and recovery are captured.
- [ ] Jenkins email notification is received and captured.
- [ ] Grafana Explore displays application logs from Loki.
- [ ] `.env.monitoring` and other secrets are not staged or tracked.
