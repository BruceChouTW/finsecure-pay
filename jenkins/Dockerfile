FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       docker-cli \
       git \
       curl \
       ca-certificates \
       awscli \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/kubectl \
    "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
    && install -o root -g root -m 0755 /tmp/kubectl /usr/local/bin/kubectl \
    && rm /tmp/kubectl

RUN curl -sfL \
    https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
    | sh -s -- -b /usr/local/bin v0.76.1

USER jenkins
