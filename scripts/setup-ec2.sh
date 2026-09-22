#!/usr/bin/env bash

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/SakeZuki}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/sakezuki.env}"
SWAP_FILE="/swapfile"
SWAP_SIZE="2G"

log() {
    echo
    echo "===== $1 ====="
}

log "시스템 패키지 업데이트"
sudo apt-get update
sudo apt-get install -y ca-certificates curl git

log "Swap 확인"
if ! swapon --show | grep -q .; then
    echo "Swap이 없어 ${SWAP_SIZE} Swap을 생성합니다."

    sudo fallocate -l "$SWAP_SIZE" "$SWAP_FILE"
    sudo chmod 600 "$SWAP_FILE"
    sudo mkswap "$SWAP_FILE"
    sudo swapon "$SWAP_FILE"

    if ! grep -q "^${SWAP_FILE} " /etc/fstab; then
        echo "${SWAP_FILE} none swap sw 0 0" | sudo tee -a /etc/fstab
    fi
else
    echo "기존 Swap이 있어 생성을 건너뜁니다."
fi

log "Docker 확인"
if ! command -v docker >/dev/null 2>&1; then
    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg |
        sudo tee /etc/apt/keyrings/docker.asc >/dev/null
    sudo chmod a+r /etc/apt/keyrings/docker.asc

    . /etc/os-release

    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
        ${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" |
        sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

    sudo apt-get update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
    echo "Docker가 이미 설치되어 있습니다."
fi

sudo systemctl enable --now docker

if ! groups "$USER" | grep -qw docker; then
    sudo usermod -aG docker "$USER"
fi

log "Docker 환경 확인"
sudo docker --version
sudo docker compose version

log "SakeZuki 저장소 확인"
if [ ! -d "$APP_DIR/.git" ]; then
    git clone https://github.com/ki-sd/SakeZuki.git "$APP_DIR"
else
    echo "기존 저장소를 사용합니다."
fi

log "환경변수 파일 확인"
if [ ! -f "$ENV_FILE" ]; then
    echo "환경변수 파일이 없습니다: $ENV_FILE"
    echo
    echo "먼저 다음 파일을 생성한 뒤 다시 실행하세요."
    echo "$ENV_FILE"
    exit 1
fi

required_vars=(
    MYSQL_DATABASE
    MYSQL_USER
    MYSQL_PASSWORD
    MYSQL_ROOT_PASSWORD
    GEMINI_API_KEY
    NEXT_PUBLIC_GOOGLE_MAPS_API_KEY
)

for var in "${required_vars[@]}"; do
    if ! grep -Eq "^${var}=.+" "$ENV_FILE"; then
        echo "필수 환경변수가 없거나 비어 있습니다: $var"
        exit 1
    fi
done

install -m 600 "$ENV_FILE" "$APP_DIR/.env"

cd "$APP_DIR"

log "Docker Compose 설정 검증"
sudo docker compose config --quiet

log "SakeZuki 이미지 빌드"
sudo docker compose build

log "SakeZuki 실행"
sudo docker compose up -d

log "컨테이너 상태"
sudo docker compose ps

log "배포 완료"
echo "SakeZuki 실행 완료"