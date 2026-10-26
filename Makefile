.PHONY: up up3 down logs test unit it go-test smoke local-stack load k8s-kind k8s-apply k8s-down help

help: ## lista os alvos
	@grep -E '^[a-z0-9-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

up: ## sobe TODA a stack (docker compose), roda smoke test e testes de integracao
	./scripts/up.sh

up3: ## idem, com 3 replicas de cada servico
	REPLICAS=3 ./scripts/up.sh

down: ## derruba a stack e apaga volumes
	docker compose down -v

logs: ## logs dos servicos
	docker compose logs -f order-service inventory-service payment-service

test: ## todos os testes Java (unitarios + integracao) e Go
	mvn -B -ntp verify
	cd analytics-worker && go test ./...

unit: ## so unitarios (sem Docker)
	mvn -B -ntp test -pl common,services/order-service -Dtest='OrderStateMachineTest,JitteredExponentialBackOffTest'

it: ## so os testes de integracao da saga (Testcontainers; sem Docker usa infra embarcada)
	mvn -B -ntp verify -pl integration-tests -am

go-test: ## testes do analytics-worker (defina TEST_DATABASE_URL para os testes com PostgreSQL)
	cd analytics-worker && go test -v ./...

smoke: ## smoke test HTTP contra BASE_URL (padrao http://localhost:8080)
	./scripts/smoke-test.sh

local-stack: ## stack completa SEM Docker (PostgreSQL/Kafka/Redis/Debezium embarcados)
	./scripts/local-stack.sh start 1

load: ## carga k6: antes/depois de escalar (precisa de k6 no PATH)
	./scripts/load-test.sh 1-replica 1 60 60s
	./scripts/load-test.sh 3-replicas 3 60 60s

k8s-kind: ## cria cluster kind, builda/carrega imagens e aplica o overlay kind (1 replica; cabe em 8 GB)
	kind create cluster --name pedidos-saga --config k8s/kind-config.yaml
	docker build --build-arg SERVICE=order-service -t saga/order-service:local .
	docker build --build-arg SERVICE=inventory-service -t saga/inventory-service:local .
	docker build --build-arg SERVICE=payment-service -t saga/payment-service:local .
	docker build -t saga/analytics-worker:local analytics-worker
	kind load docker-image --name pedidos-saga saga/order-service:local saga/inventory-service:local saga/payment-service:local saga/analytics-worker:local
	$(MAKE) k8s-apply OVERLAY=overlays/kind

OVERLAY ?= base
k8s-apply: ## aplica os manifests (OVERLAY=base | overlays/kind | overlays/keda) no cluster atual
	kubectl kustomize --load-restrictor=LoadRestrictionsNone k8s/$(OVERLAY) | kubectl apply -f -
	kubectl -n pedidos-saga rollout status statefulset/kafka --timeout=300s
	kubectl -n pedidos-saga wait --for=condition=available deployment --all --timeout=600s

k8s-down: ## remove o cluster kind
	kind delete cluster --name pedidos-saga
