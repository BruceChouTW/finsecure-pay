pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    parameters {
        choice(
            name: 'ACTION',
            choices: ['CI_ONLY', 'DEPLOY', 'ROLLBACK'],
            description: 'CI_ONLY: build and test without EKS deployment. DEPLOY: build and deploy a new version. ROLLBACK: restore a known-good revision.'
        )

        string(
            name: 'ROLLBACK_TO_REVISION',
            defaultValue: '0',
            description: 'Kubernetes revision to restore. Use 0 to undo the latest rollout; otherwise enter a known-good revision number.'
        )
    }

    triggers {
        pollSCM('H/2 * * * *')
    }

    environment {
        DOCKER_IMAGE = 'brucechoutw/finsecure-pay'

        AWS_REGION = 'us-east-1'
        EKS_CLUSTER_NAME = 'finsecure-capstone'

        K8S_NAMESPACE = 'default'
        K8S_DEPLOYMENT = 'finsecure-pay'
        K8S_CONTAINER = 'finsecure-pay'
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
                sh 'git log -1 --oneline'
            }
        }

        stage('Build') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw -B -DskipTests clean compile'
            }
        }

        stage('Test') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                sh './mvnw -B test'
            }
        }

        stage('Package') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                sh './mvnw -B -DskipTests package'
                sh '''
                    test -n "$(find target -maxdepth 1 -name "*.jar" -type f -print -quit)"
                '''
            }
        }

        stage('Security Checks') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                sh '''
                    trivy fs \
                      --scanners vuln,secret,misconfig \
                      --exit-code 0 \
                      --format table \
                      --skip-dirs .git \
                      .
                '''
            }
        }

        stage('Container Build') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                sh '''
                    docker build \
                      -t ${DOCKER_IMAGE}:${BUILD_NUMBER} \
                      -t ${DOCKER_IMAGE}:latest \
                      .
                '''
            }
        }

        stage('Push') {
            when {
                expression { params.ACTION != 'ROLLBACK' }
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'dockerhub-credentials',
                        usernameVariable: 'DOCKERHUB_USERNAME',
                        passwordVariable: 'DOCKERHUB_PASSWORD'
                    )
                ]) {
                    sh '''
                        set -eu

                        export DOCKER_CONFIG="$(mktemp -d)"
                        trap 'docker logout >/dev/null 2>&1 || true; rm -rf "$DOCKER_CONFIG"' EXIT

                        echo "$DOCKERHUB_PASSWORD" | docker login \
                          --username "$DOCKERHUB_USERNAME" \
                          --password-stdin

                        docker push ${DOCKER_IMAGE}:${BUILD_NUMBER}
                        docker push ${DOCKER_IMAGE}:latest
                    '''
                }
            }
        }

        stage('Deploy to EKS') {
            when {
                expression { params.ACTION == 'DEPLOY' }
            }
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'aws-credentials',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    withEnv([
                        "AWS_REGION=${env.AWS_REGION}",
                        "AWS_DEFAULT_REGION=${env.AWS_REGION}",
                        "AWS_PAGER=",
                        "KUBECONFIG=${env.WORKSPACE}/.kube/config"
                    ]) {
                        sh '''
                            set -eu

                            mkdir -p "$(dirname "$KUBECONFIG")"

                            echo "AWS identity:"
                            aws sts get-caller-identity

                            aws eks update-kubeconfig \
                              --region "$AWS_REGION" \
                              --name "$EKS_CLUSTER_NAME" \
                              --kubeconfig "$KUBECONFIG"

                            echo "Checking target Deployment..."
                            kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE"

                            CONTAINER_NAMES="$(kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              -o jsonpath='{.spec.template.spec.containers[*].name}')"

                            case " $CONTAINER_NAMES " in
                              *" $K8S_CONTAINER "*) ;;
                              *)
                                echo "Container '$K8S_CONTAINER' was not found."
                                echo "Available containers: $CONTAINER_NAMES"
                                exit 1
                                ;;
                            esac

                            EXPECTED_IMAGE="${DOCKER_IMAGE}:${BUILD_NUMBER}"

                            kubectl annotate deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              "kubernetes.io/change-cause=Jenkins build #${BUILD_NUMBER}: ${EXPECTED_IMAGE}" \
                              --overwrite

                            echo "Deploying image: $EXPECTED_IMAGE"

                            kubectl set image \
                              "deployment/$K8S_DEPLOYMENT" \
                              "$K8S_CONTAINER=$EXPECTED_IMAGE" \
                              -n "$K8S_NAMESPACE"

                            kubectl rollout status \
                              "deployment/$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              --timeout=180s

                            ACTUAL_IMAGE="$(kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              -o jsonpath="{.spec.template.spec.containers[?(@.name=='${K8S_CONTAINER}')].image}")"

                            if [ "$ACTUAL_IMAGE" != "$EXPECTED_IMAGE" ]; then
                              echo "Unexpected image: $ACTUAL_IMAGE"
                              echo "Expected image: $EXPECTED_IMAGE"
                              exit 1
                            fi

                            DESIRED_REPLICAS="$(kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" -o jsonpath='{.spec.replicas}')"

                            READY_REPLICAS="$(kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" -o jsonpath='{.status.readyReplicas}')"

                            READY_REPLICAS="${READY_REPLICAS:-0}"

                            if [ "$READY_REPLICAS" != "$DESIRED_REPLICAS" ]; then
                              echo "Ready replicas: $READY_REPLICAS / $DESIRED_REPLICAS"
                              exit 1
                            fi

                            echo "Deployment image: $ACTUAL_IMAGE"
                            echo "Ready replicas: $READY_REPLICAS / $DESIRED_REPLICAS"

                            kubectl get deployment,replicaset,pods \
                              -n "$K8S_NAMESPACE" -o wide

                            kubectl rollout history \
                              "deployment/$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE"
                        '''
                    }
                }
            }
        }

        stage('Verify Rollout') {
            when {
                expression { params.ACTION == 'DEPLOY' }
            }
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'aws-credentials',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    withEnv([
                        "AWS_REGION=${env.AWS_REGION}",
                        "AWS_DEFAULT_REGION=${env.AWS_REGION}",
                        "AWS_PAGER=",
                        "KUBECONFIG=${env.WORKSPACE}/.kube/config"
                    ]) {
                        sh '''
                            set -eu

                            mkdir -p "$(dirname "$KUBECONFIG")"

                            aws eks update-kubeconfig \
                              --region "$AWS_REGION" \
                              --name "$EKS_CLUSTER_NAME" \
                              --kubeconfig "$KUBECONFIG"

                            LB_INFO="$(kubectl get services \
                              -n "$K8S_NAMESPACE" \
                              -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.status.loadBalancer.ingress[0].hostname}{" "}{.spec.ports[0].port}{"\\n"}{end}' \
                              | head -n 1)"

                            LB_HOST="$(printf '%s\\n' "$LB_INFO" | awk '{print $1}')"
                            LB_PORT="$(printf '%s\\n' "$LB_INFO" | awk '{print $2}')"

                            if [ -z "$LB_HOST" ] || [ -z "$LB_PORT" ]; then
                              echo "Could not find a ready LoadBalancer hostname and port."
                              kubectl get services -n "$K8S_NAMESPACE" -o wide
                              exit 1
                            fi

                            echo "Checking application health: http://${LB_HOST}:${LB_PORT}/health"

                            curl --fail --silent --show-error \
                              --retry 10 \
                              --retry-connrefused \
                              --retry-delay 5 \
                              --connect-timeout 5 \
                              --max-time 15 \
                              "http://${LB_HOST}:${LB_PORT}/health"

                            echo
                            echo "Application health check passed."
                        '''
                    }
                }
            }
        }

        stage('Rollback') {
            when {
                expression { params.ACTION == 'ROLLBACK' }
            }
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'aws-credentials',
                        usernameVariable: 'AWS_ACCESS_KEY_ID',
                        passwordVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    withEnv([
                        "AWS_REGION=${env.AWS_REGION}",
                        "AWS_DEFAULT_REGION=${env.AWS_REGION}",
                        "AWS_PAGER=",
                        "KUBECONFIG=${env.WORKSPACE}/.kube/config",
                        "ROLLBACK_REVISION=${params.ROLLBACK_TO_REVISION}"
                    ]) {
                        sh '''
                            set -eu

                            case "$ROLLBACK_REVISION" in
                              ''|*[!0-9]*)
                                echo "ROLLBACK_TO_REVISION must be a non-negative integer."
                                exit 2
                                ;;
                            esac

                            mkdir -p "$(dirname "$KUBECONFIG")"

                            aws eks update-kubeconfig \
                              --region "$AWS_REGION" \
                              --name "$EKS_CLUSTER_NAME" \
                              --kubeconfig "$KUBECONFIG"

                            echo "Current rollout history:"
                            kubectl rollout history \
                              "deployment/$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE"

                            if [ "$ROLLBACK_REVISION" = "0" ]; then
                              echo "Rolling back to the previous Deployment revision..."
                              kubectl rollout undo \
                                "deployment/$K8S_DEPLOYMENT" \
                                -n "$K8S_NAMESPACE"
                            else
                              echo "Rolling back to revision $ROLLBACK_REVISION..."
                              kubectl rollout undo \
                                "deployment/$K8S_DEPLOYMENT" \
                                -n "$K8S_NAMESPACE" \
                                --to-revision="$ROLLBACK_REVISION"
                            fi

                            kubectl rollout status \
                              "deployment/$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              --timeout=180s

                            echo "Deployment after rollback:"
                            kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              -o wide

                            kubectl get deployment "$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE" \
                              -o jsonpath='Image: {.spec.template.spec.containers[*].image}{"\\n"}Ready replicas: {.status.readyReplicas}{"\\n"}'

                            kubectl get replicaset,pods \
                              -n "$K8S_NAMESPACE" -o wide

                            echo "Rollout history after rollback:"
                            kubectl rollout history \
                              "deployment/$K8S_DEPLOYMENT" \
                              -n "$K8S_NAMESPACE"
                        '''
                    }
                }
            }
        }
    }

    post {
        always {
            junit(
                testResults: 'target/surefire-reports/*.xml',
                allowEmptyResults: true
            )

            archiveArtifacts(
                artifacts: 'target/*.jar',
                allowEmptyArchive: true
            )
        }

        success {
            echo 'Pipeline completed successfully.'
        }

        failure {
            echo 'Pipeline failed. Check the failed stage and test reports.'
        }
    }
}
