#!/usr/bin/env bash

set -Eeuo pipefail

ENV_FILE="${ENV_FILE:-/home/ubuntu/jenkins-agent.env}"
AGENT_DIR="/home/ubuntu/jenkins-agent"
SERVICE_NAME="jenkins-agent"
AGENT_USER="ubuntu"

log() {
    echo
    echo "===== $1 ====="
}

log "Jenkins Agent 환경변수 확인"
if [ ! -f "$ENV_FILE" ]; then
    echo "환경변수 파일이 없습니다: $ENV_FILE"
    exit 1
fi

required_vars=(
    JENKINS_URL
    JENKINS_AGENT_NAME
    JENKINS_AGENT_SECRET
)

for var in "${required_vars[@]}"; do
    if ! grep -Eq "^${var}=.+" "$ENV_FILE"; then
        echo "필수 환경변수가 없거나 비어 있습니다: $var"
        exit 1
    fi
done

set -a
source "$ENV_FILE"
set +a

log "Java 확인"
if ! command -v java >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jre-headless
else
    echo "Java가 이미 설치되어 있습니다."
fi

java -version

log "Jenkins Agent 디렉터리 생성"
mkdir -p "$AGENT_DIR"

log "agent.jar 다운로드"
curl -fsSL "${JENKINS_URL%/}/jnlpJars/agent.jar" -o "$AGENT_DIR/agent.jar"

log "Docker 그룹 확인"
if ! id -nG "$AGENT_USER" | grep -qw docker; then
    sudo usermod -aG docker "$AGENT_USER"
fi

log "systemd 서비스 생성"
sudo tee "/etc/systemd/system/${SERVICE_NAME}.service" >/dev/null <<EOF
[Unit]
Description=Jenkins Agent
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
Type=simple
User=${AGENT_USER}
WorkingDirectory=${AGENT_DIR}
ExecStart=/usr/bin/java -jar ${AGENT_DIR}/agent.jar -url ${JENKINS_URL} -secret ${JENKINS_AGENT_SECRET} -name ${JENKINS_AGENT_NAME} -webSocket -workDir ${AGENT_DIR}
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

log "Jenkins Agent 시작"
sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

log "Jenkins Agent 상태"
sudo systemctl --no-pager --full status "$SERVICE_NAME"