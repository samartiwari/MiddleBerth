#!/usr/bin/env bash
# Build the cluster, put the system on it, seed it.
#
#   k8s/up.sh
#
# Takes a few minutes the first time. Everything lives in Docker containers on
# this laptop and k8s/down.sh removes all of it.

set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.local/bin:$PATH"

CLUSTER=middleberth
IMAGES="booking-service search-service payment-service notification-service gateway"

# Docker here is installed as a snap, which has its own private /tmp. kind stages
# images through a temporary directory and hands the path to "docker save", so the
# default /tmp path does not exist as far as the daemon is concerned:
#   invalid output path: stat /tmp/images-tar.../: no such file or directory
# A plain directory under $HOME is visible to both. NOT a hidden one — snap's
# confinement allows $HOME but refuses anything starting with a dot.
export TMPDIR="$HOME/kind-tmp"
mkdir -p "$TMPDIR"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    echo "== creating the cluster (1 control-plane, 2 workers)"
    kind create cluster --config k8s/kind-cluster.yaml --wait 120s
fi

echo "== loading the images into the cluster"
# The cluster's nodes are containers with their own image store — they cannot see
# the laptop's. Without this, every pod sits in ErrImagePull looking for these
# names on Docker Hub.
for image in $IMAGES; do
    kind load docker-image "middleberth-$image:latest" --name "$CLUSTER" >/dev/null
    echo "   middleberth-$image"
done

echo "== metrics-server (the autoscaler has nothing to measure without it)"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml >/dev/null
# kind's kubelets serve metrics with a self-signed certificate, so tell
# metrics-server not to insist on verifying it. Fine here; not fine in production.
kubectl -n kube-system patch deployment metrics-server --type=json \
    -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null 2>&1 || true

echo "== databases, queue, cache"
kubectl apply -f k8s/infra.yaml >/dev/null
for d in booking-db search-db payment-db notification-db auth-db redis kafka; do
    kubectl -n middleberth rollout status deploy/$d --timeout=180s
done

echo "== the services"
kubectl apply -f k8s/services.yaml >/dev/null
for d in booking-service search-service payment-service notification-service gateway nginx; do
    kubectl -n middleberth rollout status deploy/$d --timeout=300s
done

echo "== seeding"
kubectl -n middleberth delete job seed --ignore-not-found >/dev/null
kubectl apply -f k8s/seed-job.yaml >/dev/null
kubectl -n middleberth wait --for=condition=complete job/seed --timeout=180s

echo
kubectl -n middleberth get pods -o wide
echo
echo "the system is at http://localhost:8088"
echo "autoscalers are NOT on yet — that is k8s/scale.sh"
