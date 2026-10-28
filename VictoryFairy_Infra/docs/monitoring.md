# 모니터링 (kube-prometheus-stack)

Prometheus·Alertmanager·Grafana·kube-state-metrics·node-exporter 를 클러스터에 직접 올린다.
관리형(AMP/AMG)·Grafana Cloud 대신 자체 운영을 골랐다(2026-10-21).

| 구성 | 위치 |
|------|------|
| 전용 노드그룹 `monitoring`(t3.medium 1대, taint `workload=monitoring`) | `environments/dev/main.tf` |
| EBS CSI 애드온 + IRSA | `modules/eks/ebs_csi.tf` |
| `monitoring` 네임스페이스 | `k8s/00-namespaces.yaml` |
| gp3 StorageClass(Retain) | `k8s/60-monitoring-storage.yaml` |
| Helm values | `k8s/61-kube-prometheus-stack.values.yaml` |

**차트 버전: `kube-prometheus-stack 92.11.0`** — 올릴 때 이 줄과 아래 명령을 같이 고친다.

## 설치 순서

순서가 중요하다. 노드·CSI 가 없으면 PVC 가 Pending, 시크릿이 없으면 Grafana 가 CreateContainerConfigError.

```bash
# 1) 노드그룹 + EBS CSI (plan 에 destroy/replace 가 없는지 먼저 확인)
cd environments/dev && terraform plan && terraform apply
kubectl get nodes -l workload=monitoring        # Ready 1대
kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-ebs-csi-driver

# 2) 네임스페이스·StorageClass
kubectl apply -f k8s/00-namespaces.yaml
kubectl apply -f k8s/60-monitoring-storage.yaml

# 3) Grafana 관리자 시크릿 (값은 커밋하지 않는다)
kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin \
  --from-literal=admin-password="$(openssl rand -base64 24)"

# 4) 스택 설치
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install kps prometheus-community/kube-prometheus-stack \
  --version 92.11.0 -n monitoring \
  -f k8s/61-kube-prometheus-stack.values.yaml
```

⚠ 릴리스 이름 `kps` 는 바꾸지 말 것. 리소스 이름(`kps-grafana`, `kps-kube-prometheus-stack-prometheus` …)이
여기서 파생되고, Prometheus PVC 이름도 바뀌어 기존 이력과 끊긴다.

## 확인

```bash
kubectl -n monitoring get pods -o wide          # node-exporter 외에는 전부 monitoring 노드
kubectl -n monitoring get pvc                   # Bound 2개(prometheus 10Gi, grafana 2Gi)
```

## 접속 (공개하지 않는다)

```bash
kubectl -n monitoring port-forward svc/kps-grafana 3000:80
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-prometheus 9090:9090
kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-alertmanager 9093:9093
```

## 업그레이드

`helm upgrade` 는 CRD 를 갱신하지 않는다. 메이저를 올릴 때는 차트 README 의 업그레이드 절에 있는
`kubectl apply --server-side -f .../crds/...` 를 먼저 실행한다.

## 아직 안 된 것

- 앱(user·quiz·chat) 지표: `micrometer-registry-prometheus` + 별도 management 포트 + ServiceMonitor
- EC2 박스(MySQL·Redis·Kafka): node_exporter·mysqld_exporter, SG 에 수집 포트 인입
- 알림 수신처: 지금은 null 리시버라 알람은 UI 에서만 보인다
- 로그(Loki)
