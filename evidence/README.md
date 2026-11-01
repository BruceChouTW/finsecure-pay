# FinSecure Pay – Project Evidence

This directory contains the implementation evidence and validation results for the FinSecure Pay Capstone Project.

## Evidence Index

| Task | Area | Evidence |
|---|---|---|
| Task 1 | Application Development | [Application Test Results](task1-application/) |
| Task 2 | Docker Containerization | [Docker Evidence](task2-docker/) |
| Task 3 | Kubernetes and AWS EKS | [EKS Deployment Evidence](task3-eks/) |
| Task 4 | CI/CD Pipeline | [CI/CD Evidence](task4-cicd/) |
| Task 5 | DevSecOps | [Security Scan Reports and SBOM](task5-devsecops/) |
| Task 6 | Monitoring and Observability | [Monitoring Evidence](task6-monitoring/) |

## Task 5 – DevSecOps

The following reports are automatically generated during Jenkins Pipeline execution:

- `trivy-fs.json`: Filesystem vulnerability scan results.
- `trivy-image.json`: Container image vulnerability scan results.
- `trivy-secrets.json`: Secret detection scan results.
- `spotbugs.xml`: Static code analysis report.
- `finsecure-pay-sbom.cdx.json`: CycloneDX Software Bill of Materials.

Pipeline screenshots demonstrate security gate enforcement, vulnerability remediation, and credential management.

## Notes

- Screenshots and reports are provided as supporting evidence of the implementation and validation activities.
- Security reports should be reviewed for sensitive information before being published.
- Evidence reflects the project state and test results captured during the Capstone implementation.
