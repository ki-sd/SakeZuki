pipeline {
    agent {
        label 'sakezuki-deploy'
    }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    environment {
        ENV_FILE='/home/ubuntu/sakezuki.env'
        COMPOSE_PROJECT_NAME='sakezuki'
    }

    stages {
        stage('Prepare') {
            steps {
                sh '''
                    set -e

                    echo "환경변수 파일 확인"
                    test -f "$ENV_FILE"

                    echo "Jenkins workspace에 환경변수 적용"
                    cp "$ENV_FILE" .env
                    chmod 600 .env

                    echo "Docker 환경 확인"
                    docker --version
                    docker compose version

                    echo "Docker Compose 설정 검증"
                    docker compose config --quiet
                '''
            }
        }

        stage('Build') {
            steps {
                sh '''
                    set -e
                    docker compose build
                '''
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                    set -e
                    docker compose up -d --remove-orphans
                '''
            }
        }

        stage('Verify') {
            steps {
                sh '''
                    set -e

                    echo "컨테이너 상태"
                    docker compose ps

                    echo "서비스 응답 확인"
                    for i in $(seq 1 12); do
                        if curl -fsS http://localhost/ >/dev/null; then
                            echo "SakeZuki 응답 확인 완료"
                            exit 0
                        fi

                        echo "서비스 시작 대기 중... ($i/12)"
                        sleep 5
                    done

                    echo "서비스 응답 확인 실패"
                    docker compose logs --tail=100
                    exit 1
                '''
            }
        }
    }

    post {
        success {
            echo 'SakeZuki 배포 완료'
        }

        failure {
            echo 'SakeZuki 배포 실패'
            sh 'docker compose ps || true'
        }

        always {
            sh 'rm -f .env'
        }
    }
}