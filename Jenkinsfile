pipeline {
    agent {
        label 'sakezuki-deploy'
    }

    options {
        // 같은 서버의 Compose 서비스를 두 배포가 동시에 바꾸지 않도록 직렬화한다.
        disableConcurrentBuilds()
    }

    environment {
        ENV_FILE='/home/ubuntu/sakezuki.env'
        COMPOSE_PROJECT_NAME='sakezuki'
    }

    stages {
        stage('Prepare') {
            // 비밀값은 저장소에 두지 않고 서버의 환경변수 파일에서 작업 공간으로 가져온다.
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
            // 컨테이너가 시작된 직후의 준비 시간을 고려해 HTTP 응답을 여러 번 확인한다.
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
            // 배포 결과와 관계없이 작업 공간의 환경변수 복사본을 제거한다.
            sh 'rm -f .env'
        }
    }
}