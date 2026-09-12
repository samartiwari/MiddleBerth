#!/usr/bin/env bash
# Turn the autoscalers on, and watch what they do.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.local/bin:$PATH"

kubectl apply -f k8s/hpa.yaml
echo "waiting for metrics-server to start reporting CPU"
for _ in $(seq 1 40); do
    if kubectl -n middleberth get hpa booking-service -o jsonpath='{.status.currentMetrics}' 2>/dev/null | grep -q cpu; then
        break
    fi
    sleep 5
done
kubectl -n middleberth get hpa
